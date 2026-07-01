package co.subpilot.proration;

import co.subpilot.plan.entity.Plan;
import co.subpilot.subscription.entity.Subscription;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure calculation logic for mid-cycle plan changes (PRD §9).
 *
 * No side effects, no persistence, no Nomba calls — just the math. This is
 * deliberately separated from ProrationService (which applies the result —
 * writes the ProrationRecord, decides whether to charge immediately or
 * credit the next invoice, calls Nomba) so the calculation itself is easy
 * to unit test and reason about independently of everything around it.
 *
 * Formula (matches PRD §9 exactly):
 *   Upgrade:
 *     1. unusedDays = days remaining in current cycle from "now"
 *     2. credit = (unusedDays / cycleDays) * oldPlanAmount
 *     3. newPlanCharge = (unusedDays / cycleDays) * newPlanAmount
 *        — i.e. the new plan's pro-rata cost for the SAME remaining days,
 *        not a full new cycle. This is the detail most naive implementations
 *        get wrong: they charge the new plan's full price today instead of
 *        only the fraction covering the days actually remaining.
 *     4. netCharge = newPlanCharge - credit
 *     5. netCharge > 0  -> charge immediately
 *        netCharge <= 0 -> apply as credit to next invoice (rare for an
 *                           upgrade, but possible with unusual amount ratios)
 *
 *   Downgrade:
 *     Same credit/charge math, but policy typically defers the actual rate
 *     change to next cycle and only carries the credit forward — see
 *     ProrationPolicy below for how each policy interprets the same numbers.
 *
 * All amounts are minor units (kobo), matching the rest of the codebase.
 */
public final class ProrationCalculator {

    private ProrationCalculator() {}

    public record ProrationResult(
            long cycleDays,
            long unusedDays,
            long creditAmount,      // unused value of the OLD plan, in minor units
            long newPlanProrated,   // pro-rata cost of the NEW plan for the remaining days
            long netChargeToday,    // max(0, newPlanProrated - creditAmount)
            long netCreditForward   // max(0, creditAmount - newPlanProrated)
    ) {}

    /**
     * Calculates the proration breakdown for changing from the subscription's
     * current plan to a new plan, evaluated at "now".
     *
     * @param subscription the subscription being changed (must have a valid
     *                      currentPeriodStart/currentPeriodEnd — the cycle
     *                      the customer already paid for)
     * @param oldPlan       the plan being moved away from
     * @param newPlan       the plan being moved to
     * @param now           evaluation instant — passed explicitly (rather
     *                      than calling Instant.now() internally) so this
     *                      stays a pure, deterministic, testable function
     */
    public static ProrationResult calculate(Subscription subscription, Plan oldPlan, Plan newPlan, Instant now) {
        Instant periodStart = subscription.getCurrentPeriodStart();
        Instant periodEnd = subscription.getCurrentPeriodEnd();

        long cycleDays = Math.max(1, Duration.between(periodStart, periodEnd).toDays());
        long elapsedDays = Duration.between(periodStart, now).toDays();
        long unusedDays = Math.max(0, Math.min(cycleDays, cycleDays - elapsedDays));

        double fractionRemaining = (double) unusedDays / (double) cycleDays;

        long creditAmount = Math.round(oldPlan.getAmount() * fractionRemaining);
        long newPlanProrated = Math.round(newPlan.getAmount() * fractionRemaining);

        long netChargeToday = Math.max(0, newPlanProrated - creditAmount);
        long netCreditForward = Math.max(0, creditAmount - newPlanProrated);

        return new ProrationResult(
                cycleDays, unusedDays, creditAmount, newPlanProrated, netChargeToday, netCreditForward
        );
    }

    /** True when the new plan costs more per cycle than the old one. */
    public static boolean isUpgrade(Plan oldPlan, Plan newPlan) {
        return newPlan.getAmount() > oldPlan.getAmount();
    }
}