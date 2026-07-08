package co.subpilot.fee.controller;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.fee.dto.FeeDtos;
import co.subpilot.fee.entity.PlatformFee;
import co.subpilot.fee.repository.PlatformFeeRepository;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.utils.PlatformFeePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Merchant-facing endpoints for understanding SubPilot's fee on their
 * subscriptions. Maps to a "Fees" card on /app/analytics and
 * /app/settings/billing in the frontend.
 *
 * Merchants can SEE their fee rate here but cannot change it themselves —
 * rate overrides are an internal/admin operation (see AdminFeeController
 * if/when an admin console is built). This mirrors how Stripe/Paystack
 * present their own pricing to connected merchants.
 */
@RestController
@RequestMapping("/v1/fees")
@RequiredArgsConstructor
public class FeeController {

    private final PlatformFeeRepository platformFeeRepository;
    private final MerchantRepository merchantRepository;
    private final PlatformFeePolicy feePolicy;

    @Value("${subpilot.fees.default-bps:150}")
    private int defaultBps;

    @Value("${subpilot.fees.default-fixed-minor:10000}")
    private long defaultFixedMinor;

    /** GET /v1/fees/rate — what rate currently applies to this merchant. */
    @GetMapping("/rate")
    public ResponseEntity<FeeDtos.MerchantFeeRateResponse> getRate() {
        String merchantId = TenantContext.requireMerchantId();
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));

        boolean isOverride = merchant.getFeeBps() != null || merchant.getFeeFixedMinor() != null;
        int bps = merchant.getFeeBps() != null ? merchant.getFeeBps() : defaultBps;
        long fixed = merchant.getFeeFixedMinor() != null ? merchant.getFeeFixedMinor() : defaultFixedMinor;

        return ResponseEntity.ok(new FeeDtos.MerchantFeeRateResponse(bps, fixed, isOverride));
    }

    /**
     * GET /v1/fees/summary?days=30 — total fees taken from this merchant in
     * the trailing window. Powers the "SubPilot fees" stat on the analytics
     * dashboard, distinct from the merchant's own MRR/revenue numbers.
     */
    @GetMapping("/summary")
    public ResponseEntity<FeeDtos.FeeSummaryResponse> getSummary(
            @RequestParam(defaultValue = "30") int days
    ) {
        String merchantId = TenantContext.requireMerchantId();
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        long totalFee = platformFeeRepository.sumFeesByMerchantSince(merchantId, since);
        long totalGross = platformFeeRepository.sumGrossByMerchantSince(merchantId, since);
        long totalNet = totalGross - totalFee;

        return ResponseEntity.ok(new FeeDtos.FeeSummaryResponse(
                totalGross, totalFee, totalNet, "NGN", since.toString(), Instant.now().toString()
        ));
    }

    /** GET /v1/fees/ledger — raw fee ledger rows for export / reconciliation. */
    @GetMapping("/ledger")
    public ResponseEntity<Page<PlatformFee>> getLedger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int perPage
    ) {
        String merchantId = TenantContext.requireMerchantId();
        Page<PlatformFee> result = platformFeeRepository.findByMerchantIdOrderByCreatedAtDesc(
                merchantId, PageRequest.of(page, Math.min(perPage, 100)));
        return ResponseEntity.ok(result);
    }

    /** GET /v1/fees/invoices/{invoiceId} — fee breakdown for a single invoice. */
    @GetMapping("/invoices/{invoiceId}")
    public ResponseEntity<FeeDtos.InvoiceFeeBreakdownResponse> getInvoiceBreakdown(@PathVariable String invoiceId) {
        String merchantId = TenantContext.requireMerchantId();
        PlatformFee fee = platformFeeRepository.findByInvoiceId(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("platform_fee_for_invoice", invoiceId));

        return ResponseEntity.ok(new FeeDtos.InvoiceFeeBreakdownResponse(
                fee.getInvoiceId(), fee.getGrossAmount(), fee.getFeeAmount(), fee.getNetAmount(),
                fee.getFeeBpsApplied(), fee.getFeeFixedApplied(), fee.getCurrency()
        ));
    }
}