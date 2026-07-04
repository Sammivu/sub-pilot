package co.subpilot.internal.merchant.dto;

import co.subpilot.internal.merchant.service.InternalMerchantService;
import co.subpilot.merchant.entity.Merchant;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InternalMerchantDtos {

    public record MerchantListItem(
            String merchantId, String businessName, String email, String slug,
            String status, String feeSource, String createdAt, String updatedAt
    ) {
        public static MerchantListItem from(Merchant m, InternalMerchantService.EffectiveFee fee) {
            return new MerchantListItem(
                    m.getId(), m.getBusinessName(), m.getEmail(), m.getSlug(), m.getStatus(),
                    fee.feeSource(),
                    m.getCreatedAt() != null ? m.getCreatedAt().toString() : null,
                    m.getUpdatedAt() != null ? m.getUpdatedAt().toString() : null
            );
        }
    }

    public record MerchantDetail(
            String merchantId, String businessName, String email, String slug, String status,
            String feeSource, int effectiveFeeBps, long effectiveFixedFeeMinor,
            Integer overrideFeeBps, Long overrideFixedFeeMinor,
            String createdAt, String updatedAt
    ) {
        public static MerchantDetail from(Merchant m, InternalMerchantService.EffectiveFee fee) {
            return new MerchantDetail(
                    m.getId(), m.getBusinessName(), m.getEmail(), m.getSlug(), m.getStatus(),
                    fee.feeSource(), fee.feeBps(), fee.fixedFeeMinor(),
                    m.getFeeBps(), m.getFeeFixedMinor(),
                    m.getCreatedAt() != null ? m.getCreatedAt().toString() : null,
                    m.getUpdatedAt() != null ? m.getUpdatedAt().toString() : null
            );
        }
    }

    public record StatusUpdateRequest(@NotBlank String status, @NotBlank String reason) {}

    public record MerchantFeeResponse(
            String feeSource, int platformDefaultFeeBps, long platformDefaultFixedFeeMinor,
            Integer overrideFeeBps, Long overrideFixedFeeMinor,
            int effectiveFeeBps, long effectiveFixedFeeMinor
    ) {}

    public record FeeOverrideRequest(
            @NotNull @Min(0) Integer overrideFeeBps,
            @NotNull @Min(0) Long overrideFixedFeeMinor,
            @NotBlank String reason
    ) {}

    public record RemoveOverrideRequest(@NotBlank String reason) {}
}