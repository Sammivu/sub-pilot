package co.subpilot.analytics.service;

import co.subpilot.analytics.MrrCalculator;
import co.subpilot.analytics.dto.AnalyticsDtos;
import co.subpilot.dunning.repository.DunningExecutionRepository;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes every metric and chart series in PRD §6.8 (Analytics Dashboard).
 *
 * All money figures are in minor units (kobo) throughout — formatting for
 * display is the frontend's job.
 *
 * "Rolling 30 days" (churn rate) and the requested date range (everything
 * else) are kept as two independently-controllable window parameters,
 * matching how the PRD describes churn specifically as a rolling-30-day
 * metric distinct from the date-range-selectable charts.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final DunningExecutionRepository dunningExecutionRepository;

    // ── Headline metrics ─────────────────────────────────────────────────────

    public AnalyticsDtos.AnalyticsSummary getSummary(String merchantId, int rangeDays) {
        Instant rangeStart = Instant.now().minus(rangeDays, ChronoUnit.DAYS);
        Instant churnWindowStart = Instant.now().minus(30, ChronoUnit.DAYS);

        long mrr = computeMrr(merchantId);
        long activeSubscribers = subscriptionRepository.countByMerchantIdAndStatus(merchantId, SubscriptionStatus.active);

        long cancelledLast30 = subscriptionRepository.countCancelledSince(merchantId, churnWindowStart);
        double churnRate = activeSubscribers > 0
                ? (cancelledLast30 * 100.0) / (activeSubscribers + cancelledLast30)
                : 0.0;

        long succeeded = paymentAttemptRepository.countSucceededByMerchantSince(merchantId, rangeStart);
        long attempted = paymentAttemptRepository.countAttemptedByMerchantSince(merchantId, rangeStart);
        double successRate = attempted > 0 ? (succeeded * 100.0) / attempted : 0.0;

        long failedCount = invoiceRepository.countByMerchantIdAndStatus(merchantId, "failed");
        long failedValue = invoiceRepository.sumFailedAmount(merchantId);

        long newSubscribers = subscriptionRepository.countCreatedSince(merchantId, rangeStart);

        return new AnalyticsDtos.AnalyticsSummary(
                mrr, activeSubscribers, round2(churnRate), round2(successRate),
                failedCount, failedValue, newSubscribers,
                rangeStart.toString(), Instant.now().toString()
        );
    }

    /**
     * MRR: sum of the monthly-equivalent price of every currently-active
     * subscription's plan. See MrrCalculator for why this isn't just
     * "revenue collected this month."
     */
    private long computeMrr(String merchantId) {
        List<Subscription> active = subscriptionRepository
                .findByMerchantIdAndStatus(merchantId, SubscriptionStatus.active, Pageable.unpaged())
                .getContent();

        if (active.isEmpty()) return 0L;

        Set<String> planIds = active.stream().map(Subscription::getPlanId).collect(Collectors.toSet());
        Map<String, Plan> plansById = new HashMap<>();
        planRepository.findAllById(planIds).forEach(p -> plansById.put(p.getId(), p));

        long total = 0L;
        for (Subscription sub : active) {
            Plan plan = plansById.get(sub.getPlanId());
            if (plan != null) {
                total += MrrCalculator.monthlyEquivalent(plan);
            }
            // A subscription whose plan was deleted/missing contributes 0 —
            // this shouldn't happen in practice (plans are archived, never
            // hard-deleted) but we don't want a dangling reference to throw.
        }
        return total;
    }

    // ── Charts ───────────────────────────────────────────────────────────────

    public AnalyticsDtos.RevenueOverTimeChart getRevenueOverTime(String merchantId, int rangeDays, String granularity) {
        Instant since = Instant.now().minus(rangeDays, ChronoUnit.DAYS);
        List<Invoice> paid = invoiceRepository.findPaidSinceOrderByPaidAtAsc(merchantId, since);

        Map<String, Long> buckets = new TreeMap<>();
        for (Invoice inv : paid) {
            String bucket = bucketKey(inv.getPaidAt(), granularity);
            buckets.merge(bucket, inv.getAmount(), Long::sum);
        }

        List<AnalyticsDtos.ChartPoint> points = buckets.entrySet().stream()
                .map(e -> new AnalyticsDtos.ChartPoint(e.getKey(), e.getValue()))
                .toList();

        return new AnalyticsDtos.RevenueOverTimeChart(points, granularity);
    }

    public AnalyticsDtos.SubscriptionGrowthChart getSubscriptionGrowth(String merchantId, int rangeDays, String granularity) {
        Instant since = Instant.now().minus(rangeDays, ChronoUnit.DAYS);
        List<Subscription> created = subscriptionRepository.findCreatedSinceOrderByCreatedAtAsc(merchantId, since);

        Map<String, Long> buckets = new TreeMap<>();
        for (Subscription sub : created) {
            String bucket = bucketKey(sub.getCreatedAt(), granularity);
            buckets.merge(bucket, 1L, Long::sum);
        }

        List<AnalyticsDtos.ChartPoint> points = buckets.entrySet().stream()
                .map(e -> new AnalyticsDtos.ChartPoint(e.getKey(), e.getValue()))
                .toList();

        return new AnalyticsDtos.SubscriptionGrowthChart(points, granularity);
    }

    /**
     * Payment success rate trend — bucketed manually rather than via a
     * group-by query, since we need succeeded/total per bucket and JPQL
     * group-by-date-truncation is awkward across H2 (tests) and Postgres
     * (prod) simultaneously. At the scale of a single merchant's payment
     * attempts over a reporting window this is fine; if this ever needs to
     * scale to millions of attempts per merchant, push the bucketing into a
     * native query instead.
     */
    public AnalyticsDtos.PaymentSuccessRateTrendChart getPaymentSuccessRateTrend(String merchantId, int rangeDays) {
        Instant since = Instant.now().minus(rangeDays, ChronoUnit.DAYS);
        List<AnalyticsDtos.ChartPoint> points = new ArrayList<>();
        ZonedDateTime cursor = since.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime end = Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

        while (!cursor.isAfter(end)) {
            Instant dayStart = cursor.toInstant();
            Instant dayEnd = cursor.plusDays(1).toInstant();

            long succeeded = paymentAttemptRepository.countSucceededByMerchantSince(merchantId, dayStart)
                    - paymentAttemptRepository.countSucceededByMerchantSince(merchantId, dayEnd);
            long attempted = paymentAttemptRepository.countAttemptedByMerchantSince(merchantId, dayStart)
                    - paymentAttemptRepository.countAttemptedByMerchantSince(merchantId, dayEnd);

            double rate = attempted > 0 ? (succeeded * 100.0) / attempted : 0.0;
            points.add(new AnalyticsDtos.ChartPoint(cursor.format(DAY_FORMAT), Math.round(rate)));
            cursor = cursor.plusDays(1);
        }

        return new AnalyticsDtos.PaymentSuccessRateTrendChart(points, "daily");
    }

    /** Dunning recovery rate trend — same day-bucketing approach as above. */
    public AnalyticsDtos.DunningRecoveryRateChart getDunningRecoveryRateTrend(String merchantId, int rangeDays) {
        Instant since = Instant.now().minus(rangeDays, ChronoUnit.DAYS);
        List<AnalyticsDtos.ChartPoint> points = new ArrayList<>();
        ZonedDateTime cursor = since.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime end = Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

        while (!cursor.isAfter(end)) {
            Instant dayStart = cursor.toInstant();
            Instant dayEnd = cursor.plusDays(1).toInstant();

            long resolved = dunningExecutionRepository.countResolvedByMerchantSince(merchantId, dayStart)
                    - dunningExecutionRepository.countResolvedByMerchantSince(merchantId, dayEnd);
            long started = dunningExecutionRepository.countStartedByMerchantSince(merchantId, dayStart)
                    - dunningExecutionRepository.countStartedByMerchantSince(merchantId, dayEnd);

            double rate = started > 0 ? (resolved * 100.0) / started : 0.0;
            points.add(new AnalyticsDtos.ChartPoint(cursor.format(DAY_FORMAT), Math.round(rate)));
            cursor = cursor.plusDays(1);
        }

        return new AnalyticsDtos.DunningRecoveryRateChart(points, "daily");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String bucketKey(Instant instant, String granularity) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        return switch (granularity) {
            case "weekly" -> zdt.toLocalDate().minusDays(zdt.getDayOfWeek().getValue() - 1).format(DAY_FORMAT);
            case "monthly" -> zdt.toLocalDate().withDayOfMonth(1).format(DAY_FORMAT);
            default -> zdt.toLocalDate().format(DAY_FORMAT); // "daily"
        };
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}