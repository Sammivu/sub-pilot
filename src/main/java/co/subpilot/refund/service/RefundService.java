package co.subpilot.refund.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.internal.admin.service.InternalAdminNotificationService;
import co.subpilot.fee.service.PlatformFeeService;
import co.subpilot.internal.audit.service.InternalAuditService;
import co.subpilot.invoice.InvoiceStatus;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.refund.RefundStatus;
import co.subpilot.refund.entity.Refund;
import co.subpilot.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Backend Gap checklist item — RefundService + RefundController.
 *
 * Refunds move real money out of SubPilot's pooled central Nomba wallet
 * (same pool every merchant's payments land in — see DisbursementService),
 * so a merchant requesting a refund does NOT immediately trigger it.
 * createRefund() only records the request (status=PENDING_APPROVAL) and
 * dispatches REFUND_CREATED; a SubPilot platform operator must call
 * approve() (via AdminRefundController) before Nomba is ever contacted.
 * reject() lets an operator decline without ever touching Nomba.
 *
 * NOTE on the admin boundary: there is currently no platform-staff user
 * model in this codebase — every User belongs to exactly one merchant
 * tenant. AdminRefundController is guarded by a static admin API key
 * (subpilot.admin.api-key) as a pragmatic MVP measure, not a real
 * cross-merchant RBAC system. Worth graduating to real admin accounts
 * later; noting it here rather than pretending it's more than it is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final InvoiceRepository invoiceRepository;
    private final PlatformFeeService platformFeeService;
    private final NombaPaymentGateway nomba;
    private final EventService eventService;
    private final InternalAuditService internalAuditService;
    private final InternalAdminNotificationService notificationService;

    @Transactional
    public Refund createRefund(String invoiceId, Long requestedAmount, String reason, String requestedByUserId) {
        String merchantId = TenantContext.requireMerchantId();

        Invoice invoice = invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));

        if (!invoice.isPaid()) {
            throw new BusinessRuleException("invoice_not_paid",
                    "Only a paid invoice can be refunded (current status: " + invoice.getStatus() + ").");
        }
        if (invoice.getPaidAt() == null) {
            throw new BusinessRuleException("invoice_missing_payment_date", "The payment timestamp for this invoice is unavailable."
            );
        }

        Instant refundDeadline = invoice.getPaidAt().plus(24, ChronoUnit.HOURS);
        if (Instant.now().isAfter(refundDeadline)) {
            throw new BusinessRuleException("refund_window_expired",
                    "Refunds can only be requested within 24 hours of payment."
            );
        }

        long alreadyRefunded = refundRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId).stream()
                .filter(r -> RefundStatus.SUCCEEDED.equals(r.getStatus())
                        || RefundStatus.PENDING.equals(r.getStatus())
                        || RefundStatus.PENDING_APPROVAL.equals(r.getStatus()))
                .mapToLong(Refund::getAmount)
                .sum();
        long refundableRemaining = invoice.getAmount() - alreadyRefunded;

        long amount = requestedAmount != null ? requestedAmount : refundableRemaining;
        if (amount <= 0 || amount > refundableRemaining) {
            throw new BusinessRuleException("invalid_refund_amount",
                    "Refund amount must be between 1 and " + refundableRemaining + " (already refunded or pending: " + alreadyRefunded + ").");
        }

        long feeReversal = platformFeeService.calculateFeeReversal(invoiceId, amount);

        // Independent of the entity's own primary key — see idempotencyKey's
        // javadoc on Refund.java. Generated now even though Nomba isn't
        // contacted until approve(), so the value is fixed at request time,
        // not decided later by whichever operator happens to approve it.
        String idempotencyKey = com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString();

        Refund refund = refundRepository.save(Refund.builder()
                .merchantId(merchantId)
                .invoiceId(invoiceId)
                .amount(amount)
                .currency(invoice.getCurrency())
                .platformFeeRefunded(feeReversal)
                .reason(reason)
                .status(RefundStatus.PENDING_APPROVAL)
                .idempotencyKey(idempotencyKey)
                .requestedByUserId(requestedByUserId)
                .build());

        eventService.record(merchantId, EventType.REFUND_CREATED, "refund", refund.getId(),
                Map.of("invoiceId", invoiceId, "amount", amount));

        notificationService.notifyRefundPendingApproval(refund.getId(), merchantId, amount);

        log.info("Refund requested, awaiting admin approval: merchant={} invoice={} refund={} amount={}",
                merchantId, invoiceId, refund.getId(), amount);

        return refund;
    }

    /**
     * Admin-only — see class javadoc on the approval boundary. This is the
     * ONLY path that ever calls nomba.initiateRefund; createRefund() never
     * does. Not tenant-scoped (an admin operates across merchants), so
     * this deliberately does NOT go through TenantContext.
     */
    @Transactional
    public Refund approve(String refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("refund", refundId));

        if (!RefundStatus.PENDING_APPROVAL.equals(refund.getStatus())) {
            throw new BusinessRuleException("refund_not_pending_approval",
                    "Refund " + refundId + " is not awaiting approval (current status: " + refund.getStatus() + ").");
        }

        Invoice invoice = invoiceRepository.findById(refund.getInvoiceId())
                .orElseThrow(() -> new ResourceNotFoundException("invoice", refund.getInvoiceId()));

        refund.setStatus(RefundStatus.PENDING);
        Refund refund1 = refundRepository.save(refund);

        String oldStatus = refund1.getStatus();

        // Deliberately synchronous — same reasoning as before: whoever's
        // watching the approval queue wants to know immediately whether it
        // went through.
        NombaPaymentGateway.RefundRequest request = new NombaPaymentGateway.RefundRequest(
                invoice.getNombaReference(), refund.getAmount(), refund.getCurrency(),
                refund.getIdempotencyKey(), refund.getReason());
        NombaPaymentGateway.RefundResponse response = nomba.initiateRefund(request);

        if (response.success()) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setNombaReference(response.reference());
            refund.setResolvedAt(Instant.now());
            refundRepository.save(refund);

            long alreadyRefunded = refundRepository.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).stream()
                    .filter(r -> RefundStatus.SUCCEEDED.equals(r.getStatus()))
                    .mapToLong(Refund::getAmount)
                    .sum();
            if (alreadyRefunded >= invoice.getAmount()) {
                invoice.setStatus(InvoiceStatus.REFUNDED);
                invoiceRepository.save(invoice);
            }

            eventService.record(refund.getMerchantId(), EventType.REFUND_SUCCEEDED, "refund", refund.getId(),
                    Map.of("invoiceId", invoice.getId(), "amount", refund.getAmount(), "nombaReference", response.reference()));
            internalAuditService.record(
                    "refund",
                    refund.getId(),
                    "refund_approved",
                    Map.of("status", oldStatus),
                    Map.of("status", refund.getStatus()),
                    null
            );
            log.info("Refund approved and succeeded: refund={} amount={}", refund.getId(), refund.getAmount());
        } else {
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailureReason(response.failureReason());
            refund.setResolvedAt(Instant.now());
            refundRepository.save(refund);

            eventService.record(refund.getMerchantId(), EventType.REFUND_FAILED, "refund", refund.getId(),
                    Map.of("invoiceId", invoice.getId(), "amount", refund.getAmount(), "failureReason",
                            response.failureReason() != null ? response.failureReason() : "unknown"));

            log.warn("Refund approved but failed at Nomba: refund={} reason={}", refund.getId(), response.failureReason());
        }

        return refund;
    }

    /** Admin-only — declines a request without ever contacting Nomba. */
    @Transactional
    public Refund reject(String refundId, String adminReason) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("refund", refundId));

        if (!RefundStatus.PENDING_APPROVAL.equals(refund.getStatus())) {
            throw new BusinessRuleException("refund_not_pending_approval",
                    "Refund " + refundId + " is not awaiting approval (current status: " + refund.getStatus() + ").");
        }

        refund.setStatus(RefundStatus.REJECTED);
        refund.setFailureReason(adminReason);
        refund.setResolvedAt(Instant.now());
        refundRepository.save(refund);

        eventService.record(refund.getMerchantId(), EventType.REFUND_REJECTED, "refund", refund.getId(),
                Map.of("invoiceId", refund.getInvoiceId(), "reason", adminReason != null ? adminReason : ""));

        log.info("Refund rejected by admin: refund={} reason={}", refund.getId(), adminReason);
        return refund;
    }

    /** Admin queue — everything awaiting a decision, across all merchants. */
    public List<Refund> listPendingApproval() {
        return refundRepository.findByStatus(RefundStatus.PENDING_APPROVAL);
    }

    public List<Refund> listForInvoice(String invoiceId) {
        String merchantId = TenantContext.requireMerchantId();
        // Confirms tenant ownership of the invoice before returning its refunds.
        invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("invoice", invoiceId));
        return refundRepository.findByInvoiceIdOrderByCreatedAtDesc(invoiceId);
    }
}