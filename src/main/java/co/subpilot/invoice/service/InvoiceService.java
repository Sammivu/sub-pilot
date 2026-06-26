package co.subpilot.invoice.service;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.fee.service.PlatformFeeService;
import co.subpilot.invoice.InvoiceStatus;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.entity.InvoiceSequence;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.invoice.repository.InvoiceSequenceRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceSequenceRepository invoiceSequenceRepository;
    private final EventService eventService;
    private final PlatformFeeService platformFeeService;

    /**
     * Convenience overload matching the call sites in BillingEngineJob and
     * SubscriptionService, which only have merchantId/subscriptionId/
     * customerId/amount/periodStart/periodEnd on hand. Defaults currency to
     * NGN, dueDate to periodStart, and prorationNote to null. Delegates to
     * generateForPeriod for the actual idempotent create-or-return logic.
     */
    @Transactional
    public Invoice createOrFind(String merchantId, String subscriptionId, String customerId,
                                long amount, Instant periodStart, Instant periodEnd) {
        return generateForPeriod(merchantId, subscriptionId, customerId, amount, "NGN",
                periodStart, periodEnd, periodStart, null);
    }

    /**
     * Generates a new invoice for a billing cycle. Idempotent per
     * (subscription_id, period_start) — if an invoice already exists for this
     * period, returns the existing one rather than creating a duplicate.
     *
     * This is the PRD §6.5 / §12.4 idempotency guarantee at the invoice level.
     */
    @Transactional
    public Invoice generateForPeriod(
            String merchantId, String subscriptionId, String customerId,
            long amount, String currency, Instant periodStart, Instant periodEnd, Instant dueDate,
            String prorationNote
    ) {
        Optional<Invoice> existing = invoiceRepository.findBySubscriptionIdAndPeriodStart(subscriptionId, periodStart);
        if (existing.isPresent()) {
            return existing.get();
        }

        Invoice invoice = new Invoice();
        invoice.setMerchantId(merchantId);
        invoice.setSubscriptionId(subscriptionId);
        invoice.setCustomerId(customerId);
        invoice.setInvoiceNumber(nextInvoiceNumber(merchantId));
        invoice.setAmount(amount);
        invoice.setCurrency(currency);
        invoice.setStatus(InvoiceStatus.PENDING);
        invoice.setDueDate(dueDate);
        invoice.setPeriodStart(periodStart);
        invoice.setPeriodEnd(periodEnd);
        invoice.setProrationNote(prorationNote);

        invoice = invoiceRepository.save(invoice);

        eventService.recordWithSubscription(merchantId, EventType.INVOICE_CREATED, "invoice", invoice.getId(),
                subscriptionId, Map.of("amount", amount, "invoiceNumber", invoice.getInvoiceNumber()));

        return invoice;
    }

    /**
     * Marks an invoice paid without a known provider reference (e.g. invoked
     * from the inbound Nomba webhook handler where the reference was already
     * recorded on the PaymentAttempt). Does NOT apply the platform fee —
     * callers that have a fresh successful charge should use the 2-arg
     * overload below so SubPilot's cut is taken exactly once per invoice.
     */
    @Transactional
    public Invoice markPaid(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));
        if (InvoiceStatus.PAID.equals(invoice.getStatus())) {
            return invoice; // idempotent — already paid, e.g. duplicate webhook delivery
        }
        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(Instant.now());
        invoice = invoiceRepository.save(invoice);

        eventService.recordWithSubscription(invoice.getMerchantId(), EventType.INVOICE_PAID, "invoice", invoice.getId(),
                invoice.getSubscriptionId(), Map.of("amount", invoice.getAmount()));

        return invoice;
    }

    /**
     * Marks an invoice paid, applies SubPilot's platform fee, and records
     * which PaymentAttempt produced the charge — giving the platform_fees
     * ledger row a real traceable link back to the attempt instead of null.
     * This is the path used by the billing engine, where a PaymentAttempt id
     * is always on hand.
     *
     * Idempotent: if the invoice is already paid (e.g. the billing job
     * re-runs after a crash but before advancing next_billing_date), the fee
     * is NOT re-applied a second time.
     */
    @Transactional
    public Invoice markPaid(String invoiceId, String nombaReference, String paymentAttemptId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));

        boolean alreadyPaid = InvoiceStatus.PAID.equals(invoice.getStatus());

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaidAt(invoice.getPaidAt() != null ? invoice.getPaidAt() : Instant.now());
        invoice.setNombaReference(nombaReference);
        invoice = invoiceRepository.save(invoice);

        if (!alreadyPaid) {
            eventService.recordWithSubscription(invoice.getMerchantId(), EventType.INVOICE_PAID, "invoice", invoice.getId(),
                    invoice.getSubscriptionId(), Map.of("amount", invoice.getAmount(), "nombaReference", nombaReference));

            // SubPilot's cut — applied exactly once, only on the transition into "paid".
            platformFeeService.applyFeeToSuccessfulCharge(invoice, paymentAttemptId);
        }

        return invoice;
    }

    /**
     * Marks an invoice paid and applies SubPilot's platform fee, without a
     * known PaymentAttempt id (e.g. the subscriber-checkout activation path,
     * where the charge happened inside Nomba's hosted checkout rather than
     * through PaymentService). The fee ledger row will have a null
     * payment_attempt_id in this case — everything else is identical to the
     * 3-arg overload above.
     */
    @Transactional
    public Invoice markPaid(String invoiceId, String nombaReference) {
        return markPaid(invoiceId, nombaReference, null);
    }

    @Transactional
    public Invoice markFailed(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));
        invoice.setStatus(InvoiceStatus.FAILED);
        return invoiceRepository.save(invoice);
    }

    @Transactional
    public Invoice voidInvoice(String merchantId, String invoiceId) {
        Invoice invoice = invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));
        invoice.setStatus(InvoiceStatus.VOID);
        invoice = invoiceRepository.save(invoice);
        eventService.record(merchantId, EventType.INVOICE_VOIDED, "invoice", invoice.getId(), null);
        return invoice;
    }

    public Page<Invoice> list(String status, int page, int perPage) {
        String merchantId = TenantContext.requireMerchantId();
        Pageable pageable = PageRequest.of(page, Math.min(perPage, 100), Sort.by("createdAt").descending());

        if (status != null && !status.isBlank()) {
            return invoiceRepository.findByMerchantIdAndStatus(merchantId, status, pageable);
        }
        return invoiceRepository.findByMerchantId(merchantId, pageable);
    }

    public Invoice getOwned(String merchantId, String invoiceId) {
        return invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));
    }

    /**
     * Generates sequential invoice numbers per merchant: INV-0001, INV-0002, ...
     * Uses pessimistic locking to be safe under concurrent billing job execution.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextInvoiceNumber(String merchantId) {
        InvoiceSequence seq = invoiceSequenceRepository.findForUpdate(merchantId)
                .orElseGet(() -> {
                    InvoiceSequence s = new InvoiceSequence();
                    s.setMerchantId(merchantId);
                    s.setLastValue(0);
                    return s;
                });
        long next = seq.getLastValue() + 1;
        seq.setLastValue(next);
        invoiceSequenceRepository.save(seq);
        return String.format("INV-%04d", next);
    }
}