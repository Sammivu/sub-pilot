package co.subpilot.internal.admin.controller;


import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.fee.repository.PlatformFeeRepository;
import co.subpilot.internal.admin.dto.InternalAdminDtos;
import co.subpilot.internal.admin.service.InternalAnalyticsService;
import co.subpilot.merchant.MerchantStatus;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.refund.RefundStatus;
import co.subpilot.refund.repository.RefundRepository;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/v1/internal/analytics")
@RequiredArgsConstructor
public class InternalAnalyticsController {

    private final InternalAnalyticsService analyticsService;
    private final MerchantRepository merchantRepository;
    private final RefundRepository refundRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlatformFeeRepository feeRepository;

    @GetMapping
    public ResponseEntity<InternalAdminDtos.AnalyticsResponse> getAnalytics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            // Merchant list filters
            @RequestParam(required = false) Long minGrossMinor,
            @RequestParam(required = false) String merchantStatus,   // active | under_review | suspended
            @RequestParam(required = false) String nameQuery,
            // Merchant list sort
            @RequestParam(defaultValue = "GROSS") InternalAnalyticsService.MerchantSortField sortBy,
            @RequestParam(defaultValue = "true")  boolean sortDesc
    ) {
        Instant toInstant   = to   != null ? Instant.parse(to)   : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : toInstant.minus(30, ChronoUnit.DAYS);

        return ResponseEntity.ok(new InternalAdminDtos.AnalyticsResponse(
                analyticsService.getPlatformSummary(fromInstant, toInstant),
                analyticsService.getMerchantBreakdown(
                        fromInstant, toInstant,
                        minGrossMinor, merchantStatus, nameQuery,
                        sortBy, sortDesc),
                analyticsService.getDailyRevenueSeries(fromInstant, toInstant),
                fromInstant,
                toInstant
        ));
    }

    @GetMapping("/merchants/{merchantId}")
    public ResponseEntity<InternalAdminDtos.MerchantRevenueRow> getMerchantStats(
            @PathVariable String merchantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        Instant toInstant   = to   != null ? Instant.parse(to)   : Instant.now();
        Instant fromInstant = from != null ? Instant.parse(from) : toInstant.minus(30, ChronoUnit.DAYS);

        return ResponseEntity.ok(
                analyticsService.getMerchantBreakdown(
                                fromInstant, toInstant, null, null, null,
                                InternalAnalyticsService.MerchantSortField.GROSS, true)
                        .stream()
                        .filter(r -> r.merchantId().equals(merchantId))
                        .findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId))
        );
    }

    @GetMapping("/summary")
    public ResponseEntity<InternalAdminDtos.SummaryResponse> summary() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant now   = Instant.now();

        return ResponseEntity.ok(new InternalAdminDtos.SummaryResponse(
                merchantRepository.countByStatus(MerchantStatus.UNDER_REVIEW),
                refundRepository.findByStatus(RefundStatus.PENDING_APPROVAL).size(),
                feeRepository.sumAllFeesBetween(since, now),
                feeRepository.sumAllGrossBetween(since, now),
                subscriptionRepository.countAllActive()
        ));
    }
}