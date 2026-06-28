package co.subpilot.analytics.controller;

import co.subpilot.analytics.dto.AnalyticsDtos;
import co.subpilot.analytics.service.AnalyticsService;
import co.subpilot.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Maps to /app/analytics in the frontend (PRD §6.8).
 *
 * All endpoints are merchant-scoped via TenantContext — same JWT-protected
 * pattern as every other operator-console controller. rangeDays defaults to
 * 30 across the board; granularity defaults to "daily" where applicable.
 */
@RestController
@RequestMapping("/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsDtos.AnalyticsSummary> getSummary(@RequestParam(defaultValue = "30") int rangeDays) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(analyticsService.getSummary(merchantId, rangeDays));
    }

    @GetMapping("/charts/revenue")
    public ResponseEntity<AnalyticsDtos.RevenueOverTimeChart> getRevenueOverTime(
            @RequestParam(defaultValue = "30") int rangeDays,
            @RequestParam(defaultValue = "daily") String granularity) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(analyticsService.getRevenueOverTime(merchantId, rangeDays, granularity));
    }

    @GetMapping("/charts/subscription-growth")
    public ResponseEntity<AnalyticsDtos.SubscriptionGrowthChart> getSubscriptionGrowth(
            @RequestParam(defaultValue = "30") int rangeDays,
            @RequestParam(defaultValue = "daily") String granularity) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(analyticsService.getSubscriptionGrowth(merchantId, rangeDays, granularity));
    }

    @GetMapping("/charts/payment-success-rate")
    public ResponseEntity<AnalyticsDtos.PaymentSuccessRateTrendChart> getPaymentSuccessRateTrend(
            @RequestParam(defaultValue = "30") int rangeDays) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(analyticsService.getPaymentSuccessRateTrend(merchantId, rangeDays));
    }

    @GetMapping("/charts/dunning-recovery-rate")
    public ResponseEntity<AnalyticsDtos.DunningRecoveryRateChart> getDunningRecoveryRateTrend(
            @RequestParam(defaultValue = "30") int rangeDays) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(analyticsService.getDunningRecoveryRateTrend(merchantId, rangeDays));
    }
}