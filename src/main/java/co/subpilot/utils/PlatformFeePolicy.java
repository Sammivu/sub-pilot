package co.subpilot.utils;

import co.subpilot.fee.dto.FeeBreakdown;
import co.subpilot.merchant.entity.Merchant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Calculates SubPilot's platform fee on a successful charge.
 *
 * Fee formula (mirrors how Stripe/Paystack/Nomba price their own platforms):
 *
 *     fee = round(gross * bps / 10_000) + fixedFee
 *     net = gross - fee
 *
 * Resolution order for the rate used:
 *   1. Merchant-level override (merchants.fee_bps / fee_fixed_minor) if set
 *   2. Platform default (subpilot.fees.default-bps / default-fixed-minor)
 *
 * Optional floor/cap (subpilot.fees.min-fee-minor / max-fee-minor) can be
 * configured to match real processor pricing pages (e.g. "1.5% + ₦100,
 * capped at ₦2,000").
 *
 * This class is pure / side-effect-free — PlatformFeeService is what
 * persists the result as a ledger row.
 */
@Service
public class PlatformFeePolicy {

    @Value("${subpilot.fees.default-bps:150}")
    private int defaultBps;

    @Value("${subpilot.fees.default-fixed-minor:10000}")
    private long defaultFixedMinor;

    @Value("${subpilot.fees.min-fee-minor:0}")
    private long minFeeMinor;

    @Value("${subpilot.fees.max-fee-minor}")
    private Long maxFeeMinor; // nullable — no cap if unset

    public FeeBreakdown calculate(Merchant merchant, long grossAmount) {
        int bps = (merchant != null && merchant.getFeeBps() != null) ? merchant.getFeeBps() : defaultBps;
        long fixed = (merchant != null && merchant.getFeeFixedMinor() != null)
                ? merchant.getFeeFixedMinor() : defaultFixedMinor;

        return calculate(grossAmount, bps, fixed);
    }

    /**
     * Overload for cases where you already know the exact rate to apply
     * (e.g. recomputing a historical invoice's fee using its stored snapshot).
     */
    public FeeBreakdown calculate(long grossAmount, int bps, long fixedMinor) {
        if (grossAmount <= 0) {
            return FeeBreakdown.zero(grossAmount);
        }

        long percentageFee = Math.round(grossAmount * (bps / 10_000.0));
        long fee = percentageFee + fixedMinor;

        if (fee < minFeeMinor) {
            fee = minFeeMinor;
        }
        if (maxFeeMinor > 0 && fee > maxFeeMinor) {
            fee = maxFeeMinor;
        }
        // Never let the fee exceed the gross amount (avoids negative payouts on tiny charges)
        if (fee > grossAmount) {
            fee = grossAmount;
        }

        long net = grossAmount - fee;
        return new FeeBreakdown(grossAmount, fee, net, bps, fixedMinor);
    }

    /**
     * Used when reversing a fee proportionally on a partial refund.
     * E.g. refunding 50% of an invoice refunds 50% of the platform fee too.
     */
    public long proportionalFeeRefund(long originalFeeAmount, long originalGrossAmount, long refundAmount) {
        if (originalGrossAmount <= 0) return 0;
        double ratio = (double) refundAmount / (double) originalGrossAmount;
        return Math.round(originalFeeAmount * ratio);
    }
}