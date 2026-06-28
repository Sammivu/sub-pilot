package co.subpilot.analytics.dto;

import java.util.List;

/**
 * Response shapes for the analytics dashboard (PRD §6.8).
 *
 * "Metrics" maps to AnalyticsSummary — the headline numbers.
 * "Charts" maps to the four time-series endpoints below it.
 */
public class AnalyticsDtos {

    /**
     * The headline metrics row. All money values in minor units (kobo) —
     * the frontend is responsible for formatting/display currency.
     */
    public record AnalyticsSummary(
            long mrr,                       // Monthly recurring revenue
            long activeSubscribers,         // Current active subscription count
            double churnRatePercent,        // Cancellations / active subscriptions, rolling 30 days
            double paymentSuccessRatePercent, // Successful charges / total attempts
            long failedPaymentsCount,       // Count of currently-failed invoices
            long failedPaymentsValue,       // Value of currently-failed invoices (minor units)
            long newSubscribersInRange,     // Acquisitions in the requested date range
            String periodStart,
            String periodEnd
    ) {}

    /** A single point on any of the time-series charts below. */
    public record ChartPoint(
            String bucket,   // ISO date string, e.g. "2026-06-01" — the start of the bucket
            long value       // money in minor units, or a plain count, depending on which chart
    ) {}

    public record RevenueOverTimeChart(List<ChartPoint> points, String granularity) {}

    public record SubscriptionGrowthChart(List<ChartPoint> points, String granularity) {}

    /** Daily payment success rate as a percentage (0-100), not a raw count. */
    public record PaymentSuccessRateTrendChart(List<ChartPoint> points, String granularity) {}

    /** Daily dunning recovery rate as a percentage (0-100) of executions that started that day. */
    public record DunningRecoveryRateChart(List<ChartPoint> points, String granularity) {}
}