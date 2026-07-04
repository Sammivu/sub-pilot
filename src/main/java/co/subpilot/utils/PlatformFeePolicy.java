package co.subpilot.utils;

import co.subpilot.fee.dto.FeeBreakdown;
import co.subpilot.internal.fee.entity.PlatformFeeDefault;
import co.subpilot.internal.fee.repository.PlatformFeeDefaultRepository;
import co.subpilot.merchant.entity.Merchant;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   2. Platform default — DB-backed (platform_fee_default table),
 *      admin-editable via PATCH /v1/internal/fees/default. The
 *      subpilot.fees.default-bps / default-fixed-minor yml values are ONLY
 *      the bootstrap seed for that row's first-ever creation — once the
 *      row exists, yml is never consulted again, even if changed.
 *
 * Optional floor/cap (subpilot.fees.min-fee-minor / max-fee-minor) can be
 * configured to match real processor pricing pages (e.g. "1.5% + ₦100,
 * capped at ₦2,000"). These remain yml-only in V1 — not part of the admin
 * dashboard spec.
 */
@Service
@RequiredArgsConstructor
public class PlatformFeePolicy {

    private final PlatformFeeDefaultRepository platformFeeDefaultRepository;

    @Value("${subpilot.fees.default-bps:150}")
    private int bootstrapBps;

    @Value("${subpilot.fees.default-fixed-minor:10000}")
    private long bootstrapFixedMinor;

    @Value("${subpilot.fees.min-fee-minor:0}")
    private long minFeeMinor;

    @Value("${subpilot.fees.max-fee-minor}")
    private Long maxFeeMinor; // nullable — no cap if unset

    public FeeBreakdown calculate(Merchant merchant, long grossAmount) {
        PlatformFeeDefault platformDefault = getOrBootstrapDefault();

        int bps = (merchant != null && merchant.getFeeBps() != null) ? merchant.getFeeBps() : platformDefault.getFeeBps();
        long fixed = (merchant != null && merchant.getFeeFixedMinor() != null)
                ? merchant.getFeeFixedMinor() : platformDefault.getFixedFeeMinor();

        return calculate(grossAmount, bps, fixed);
    }

    /**
     * Same bootstrap-on-first-read behavior as InternalFeeService.getOrBootstrap
     * — duplicated rather than depending on the internal-admin package
     * directly, since platform fee calculation is core billing logic that
     * shouldn't be coupled to the admin dashboard feature's service layer.
     */
    @Transactional
    public PlatformFeeDefault getOrBootstrapDefault() {
        return platformFeeDefaultRepository.findById(PlatformFeeDefault.SINGLETON_ID)
                .orElseGet(() -> platformFeeDefaultRepository.save(PlatformFeeDefault.builder()
                        .feeBps(bootstrapBps)
                        .fixedFeeMinor(bootstrapFixedMinor)
                        .build()));
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