package co.subpilot.fee.service;

import co.subpilot.fee.dto.FeeBreakdown;
import co.subpilot.fee.entity.PlatformFee;
import co.subpilot.fee.repository.PlatformFeeRepository;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.utils.PlatformFeePolicy;
import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies SubPilot's platform fee whenever a charge succeeds.
 *
 * Called by BillingEngineJob (renewals) and the dunning self-cure flow
 * (recovered payments) immediately after a successful PaymentAttempt —
 * never before the charge succeeds, since fees are only taken on money
 * that actually moved.
 *
 * Writes:
 *   1. An immutable PlatformFee ledger row (SubPilot's own revenue record)
 *   2. The fee/net snapshot fields on the Invoice (so merchant-facing
 *      dashboards can show "you received ₦X, SubPilot fee was ₦Y")
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformFeeService {

    private final PlatformFeePolicy feePolicy;
    private final PlatformFeeRepository platformFeeRepository;
    private final InvoiceRepository invoiceRepository;
    private final MerchantRepository merchantRepository;

    @Transactional
    public PlatformFee applyFeeToSuccessfulCharge(Invoice invoice, String paymentAttemptId) {
        Merchant merchant = merchantRepository.findById(invoice.getMerchantId()).orElse(null);
        FeeBreakdown breakdown = feePolicy.calculate(merchant, invoice.getAmount());

        // Snapshot onto the invoice so it survives future changes to fee config
        invoice.setPlatformFeeAmount(breakdown.feeAmount());
        invoice.setNetAmount(breakdown.netAmount());
        invoice.setFeeBpsApplied(breakdown.feeBpsApplied());
        invoice.setFeeFixedApplied(breakdown.feeFixedApplied());
        invoiceRepository.save(invoice);

        PlatformFee fee = new PlatformFee();
        fee.setId(UlidCreator.getMonotonicUlid().toString());
        fee.setMerchantId(invoice.getMerchantId());
        fee.setInvoiceId(invoice.getId());
        fee.setPaymentAttemptId(paymentAttemptId);
        fee.setGrossAmount(breakdown.grossAmount());
        fee.setFeeAmount(breakdown.feeAmount());
        fee.setNetAmount(breakdown.netAmount());
        fee.setCurrency(invoice.getCurrency());
        fee.setFeeBpsApplied(breakdown.feeBpsApplied());
        fee.setFeeFixedApplied(breakdown.feeFixedApplied());
        fee.setCreatedAt(Instant.now());

        fee = platformFeeRepository.save(fee);

        log.info("Platform fee applied: merchant={} invoice={} gross={} fee={} net={}",
                invoice.getMerchantId(), invoice.getId(), breakdown.grossAmount(),
                breakdown.feeAmount(), breakdown.netAmount());

        return fee;
    }

    /**
     * Used when a refund is issued — reverses a proportional share of the
     * original platform fee so the merchant isn't left out of pocket for
     * SubPilot's cut on money that was given back to the customer.
     */
    public long calculateFeeReversal(String invoiceId, long refundAmount, String merchantId) {
        return platformFeeRepository.findByMerchantIdAndInvoiceId(merchantId, invoiceId)
                .map(fee -> feePolicy.proportionalFeeRefund(fee.getFeeAmount(), fee.getGrossAmount(), refundAmount))
                .orElse(0L);
    }
}