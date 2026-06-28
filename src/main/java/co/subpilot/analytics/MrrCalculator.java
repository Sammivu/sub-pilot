package co.subpilot.analytics;

import co.subpilot.plan.entity.Plan;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Normalizes a plan's price to a monthly-equivalent amount for MRR
 * (Monthly Recurring Revenue) calculation.
 *
 * MRR is NOT simply "sum of invoices paid this calendar month" — subscriber
 * billing dates are scattered across the month (anchored to when each
 * subscriber signed up), not synchronized to a calendar boundary. The
 * standard SaaS-metric definition of MRR is: for every currently-active
 * subscription, normalize its plan price to what it would be if billed
 * monthly, then sum those normalized amounts. This is what every billing
 * platform (Stripe, Chargebee, etc.) actually means by "MRR."
 *
 * Normalization factors (multiply the plan's amount by this to get the
 * monthly-equivalent):
 *   daily     -> amount * 30        (approximate 30-day month)
 *   weekly    -> amount * 4.345     (52 weeks / 12 months)
 *   monthly   -> amount * 1
 *   quarterly -> amount / 3
 *   yearly    -> amount / 12
 *   custom    -> amount * (30 / intervalDays), where intervalDays is derived
 *                from intervalValue + intervalUnit
 */
public final class MrrCalculator {

    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);
    private static final BigDecimal WEEKS_PER_MONTH = BigDecimal.valueOf(52).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);

    private MrrCalculator() {}

    /** Returns the monthly-equivalent amount in minor units (kobo), rounded to the nearest whole unit. */
    public static long monthlyEquivalent(Plan plan) {
        BigDecimal amount = BigDecimal.valueOf(plan.getAmount());

        BigDecimal monthly = switch (plan.getBillingInterval()) {
            case daily -> amount.multiply(DAYS_PER_MONTH);
            case weekly -> amount.multiply(WEEKS_PER_MONTH);
            case monthly -> amount;
            case quarterly -> amount.divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_UP);
            case yearly -> amount.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
            case custom -> normalizeCustom(amount, plan);
        };

        return monthly.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static BigDecimal normalizeCustom(BigDecimal amount, Plan plan) {
        int value = Math.max(1, plan.getIntervalValue());
        String unit = plan.getIntervalUnit() == null ? "days" : plan.getIntervalUnit().toLowerCase();

        BigDecimal intervalDays = switch (unit) {
            case "days", "day" -> BigDecimal.valueOf(value);
            case "weeks", "week" -> BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(7));
            case "months", "month" -> BigDecimal.valueOf(value).multiply(DAYS_PER_MONTH);
            default -> BigDecimal.valueOf(value); // safe fallback, matches BillingPeriodCalculator's own fallback
        };

        if (intervalDays.signum() <= 0) {
            return amount; // defensive — avoid division by zero on a malformed plan
        }

        return amount.multiply(DAYS_PER_MONTH).divide(intervalDays, 10, RoundingMode.HALF_UP);
    }
}