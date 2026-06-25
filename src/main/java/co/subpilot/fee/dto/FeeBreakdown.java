package co.subpilot.fee.dto;

/**
 * Result of calculating SubPilot's platform fee on a given gross amount.
 * All amounts in minor units (kobo).
 */
public record FeeBreakdown(
        long grossAmount,
        long feeAmount,
        long netAmount,     // grossAmount - feeAmount — what the merchant is paid out
        int feeBpsApplied,
        long feeFixedApplied
) {
    public static FeeBreakdown zero(long grossAmount) {
        return new FeeBreakdown(grossAmount, 0, grossAmount, 0, 0);
    }
}