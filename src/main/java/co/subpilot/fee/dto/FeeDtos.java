package co.subpilot.fee.dto;

import jakarta.validation.constraints.Min;

public class FeeDtos {

    /** What a merchant sees when checking their current fee rate. */
    public record MerchantFeeRateResponse(
            int feeBps,
            long feeFixedMinor,
            boolean isOverride // true if this merchant has a custom rate, false if using platform default
    ) {}

    /** Admin/internal — set a per-merchant override. Not exposed to merchants themselves. */
    public record SetMerchantFeeOverrideRequest(
            @Min(0) Integer feeBps,        // null clears the override (falls back to platform default)
            @Min(0) Long feeFixedMinor     // null clears the override
    ) {}

    /** Summary card for /app/analytics — "SubPilot fees this period". */
    public record FeeSummaryResponse(
            long totalGrossAmount,
            long totalFeeAmount,
            long totalNetAmount,
            String currency,
            String periodStart,
            String periodEnd
    ) {}

    /** One row in the fee ledger, as the merchant sees it on a given invoice. */
    public record InvoiceFeeBreakdownResponse(
            String invoiceId,
            long grossAmount,
            long platformFeeAmount,
            long netAmount,
            int feeBpsApplied,
            long feeFixedApplied,
            String currency
    ) {}
}