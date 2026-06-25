package co.subpilot.subscription;


import co.subpilot.plan.entity.Plan;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static co.subpilot.plan.BillingInterval.*;

/**
 * Computes the next billing period boundary given a plan's interval.
 * Supports daily, weekly, monthly, quarterly, yearly, and custom (interval_value + interval_unit).
 */
public final class BillingPeriodCalculator {

    private BillingPeriodCalculator() {}

    public static Instant addInterval(Instant from, Plan plan) {
        ZonedDateTime zdt = from.atZone(ZoneOffset.UTC);

        ZonedDateTime result = switch (plan.getBillingInterval()) {
            case daily -> zdt.plusDays(1);
            case weekly -> zdt.plusWeeks(1);
            case monthly -> zdt.plusMonths(1);
            case quarterly -> zdt.plusMonths(3);
            case yearly -> zdt.plusYears(1);
            case custom -> applyCustomInterval(zdt, plan);
        };

        return result.toInstant();
    }

    private static ZonedDateTime applyCustomInterval(ZonedDateTime from, Plan plan) {
        int value = plan.getIntervalValue();
        String unit = plan.getIntervalUnit() == null ? "days" : plan.getIntervalUnit().toLowerCase();

        return switch (unit) {
            case "days", "day" -> from.plusDays(value);
            case "weeks", "week" -> from.plusWeeks(value);
            case "months", "month" -> from.plusMonths(value);
            default -> from.plusDays(value); // safe fallback
        };
    }

    public static long periodLengthInDays(Instant start, Instant end) {
        return java.time.Duration.between(start, end).toDays();
    }
}