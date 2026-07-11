package co.subpilot.internal.admin.dto;

import co.subpilot.internal.admin.entity.InternalAdmin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public class InternalAdminDtos {

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record MeResponse(String adminId, String email, String role, String displayName) {
        public static MeResponse from(InternalAdmin admin) {
            return new MeResponse(admin.getId(), admin.getEmail(), admin.getRole(), admin.getDisplayName());
        }
    }

    public record SummaryResponse(
            long pendingMerchantActivations,
            long pendingRefundApprovals,
            long subpilotRevenueLast30DaysMinor,  // ← add
            long platformGmvLast30DaysMinor,      // ← add
            long activeSubscriptions              // ← add
    ) {}

    public record MerchantRevenueRow(
            String merchantId,
            String businessName,
            String merchantStatus,        // ← add so admin can see suspended merchants in list
            long grossAmountMinor,
            long subpilotFeeMinor,
            long netAmountMinor,
            long transactionCount,
            long activeSubscriptions
    ) {}

    public record PlatformSummary(
            long totalGmvMinor,          // total money processed on the platform
            long subpilotRevenueMinor,   // SubPilot's cut
            long totalNetPaidOutMinor,   // what merchants collectively received
            long activeSubscriptions,    // live subscriptions right now
            long newSubscriptionsInWindow,
            long activeMerchants
    ) {}

    public record DailyRevenuePoint(
            String date,                 // "2026-07-01"
            long subpilotRevenueMinor,
            long gmvMinor
    ) {}

    public record AnalyticsResponse(
            PlatformSummary summary,
            List<MerchantRevenueRow> merchants,
            List<DailyRevenuePoint> dailySeries,
            Instant from,
            Instant to
    ) {}

}