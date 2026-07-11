package co.subpilot.internal.admin.service;

import co.subpilot.fee.repository.PlatformFeeRepository;
import co.subpilot.internal.admin.dto.InternalAdminDtos;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.merchant.MerchantStatus;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalAnalyticsService {

    private final PlatformFeeRepository feeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MerchantRepository merchantRepository;
    private final InvoiceRepository invoiceRepository;
    public enum MerchantSortField {
        GROSS, FEE, NET, TRANSACTIONS, ACTIVE_SUBSCRIPTIONS, NAME
    }

    public InternalAdminDtos.PlatformSummary getPlatformSummary(Instant from, Instant to) {
        long totalGmv        = feeRepository.sumAllGrossBetween(from, to);
        long subpilotRevenue = feeRepository.sumAllFeesBetween(from, to);
        long totalNetPaidOut = totalGmv - subpilotRevenue;
        long activeSubsCount = subscriptionRepository.countAllActive();
        long newSubsInWindow = subscriptionRepository.countCreatedBetween(from, to);
        long totalMerchants  = merchantRepository.countByStatus(MerchantStatus.ACTIVE);

        return new InternalAdminDtos.PlatformSummary(
                totalGmv, subpilotRevenue, totalNetPaidOut,
                activeSubsCount, newSubsInWindow, totalMerchants
        );
    }
    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    public List<InternalAdminDtos.MerchantRevenueRow> getMerchantBreakdown(
            Instant from,
            Instant to,
            Long minGrossMinor,           // filter: only merchants above this GMV
            String merchantStatus,        // filter: "active" | "under_review" | "suspended" | null = all
            String nameQuery,             // filter: partial business name search
            MerchantSortField sortBy,     // sort field
            boolean sortDesc              // true = descending
    ) {
        List<Object[]> rows = feeRepository.merchantRevenueBreakdown(from, to, minGrossMinor);

        List<String> merchantIds = rows.stream().map(r -> (String) r[0]).toList();

        Map<String, Merchant> merchants = merchantRepository.findAllById(merchantIds)
                .stream().collect(Collectors.toMap(Merchant::getId, m -> m));

        Map<String, Long> activeCounts = subscriptionRepository.activeCountPerMerchant()
                .stream().collect(Collectors.toMap(
                        r -> (String) r[0],
                        r -> asLong(r[1])   // COUNT — was (Long) r[1]
                ));

        Stream<InternalAdminDtos.MerchantRevenueRow> stream = rows.stream()
                .map(r -> {
                    String merchantId = (String) r[0];
                    Merchant m = merchants.get(merchantId);
                    return new InternalAdminDtos.MerchantRevenueRow(
                            merchantId,
                            m != null ? m.getBusinessName() : "Unknown",
                            m != null ? m.getStatus() : "unknown",
                            asLong(r[1]),   // gross
                            asLong(r[2]),   // fee
                            asLong(r[3]),   // net
                            asLong(r[4]),   // txCount — COUNT, but asLong handles both
                            activeCounts.getOrDefault(merchantId, 0L)
                    );
                });

        // ── Filters ────────────────────────────────────────────────────────────
        if (merchantStatus != null && !merchantStatus.isBlank()) {
            stream = stream.filter(r -> merchantStatus.equals(r.merchantStatus()));
        }
        if (nameQuery != null && !nameQuery.isBlank()) {
            String q = nameQuery.toLowerCase();
            stream = stream.filter(r -> r.businessName().toLowerCase().contains(q));
        }

        // ── Sort ───────────────────────────────────────────────────────────────
        Comparator<InternalAdminDtos.MerchantRevenueRow> comparator = switch (sortBy != null ? sortBy : MerchantSortField.GROSS) {
            case GROSS               -> Comparator.comparingLong(InternalAdminDtos.MerchantRevenueRow::grossAmountMinor);
            case FEE                 -> Comparator.comparingLong(InternalAdminDtos.MerchantRevenueRow::subpilotFeeMinor);
            case NET                 -> Comparator.comparingLong(InternalAdminDtos.MerchantRevenueRow::netAmountMinor);
            case TRANSACTIONS        -> Comparator.comparingLong(InternalAdminDtos.MerchantRevenueRow::transactionCount);
            case ACTIVE_SUBSCRIPTIONS -> Comparator.comparingLong(InternalAdminDtos.MerchantRevenueRow::activeSubscriptions);
            case NAME                -> Comparator.comparing(InternalAdminDtos.MerchantRevenueRow::businessName,
                    String.CASE_INSENSITIVE_ORDER);
        };

        if (sortDesc) comparator = comparator.reversed();

        return stream.sorted(comparator).toList();
    }

    public List<InternalAdminDtos.DailyRevenuePoint> getDailyRevenueSeries(Instant from, Instant to) {
        return feeRepository.dailyRevenueSeries(from, to).stream()
                .map(r -> new InternalAdminDtos.DailyRevenuePoint(
                        r[0].toString(),
                        asLong(r[1]),   // subpilot revenue
                        asLong(r[2])    // gmv
                )).toList();
    }
}