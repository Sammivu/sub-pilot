package co.subpilot.internal.fee.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class InternalFeeDtos {

    public record PlatformFeeResponse(int feeBps, long fixedFeeMinor, String updatedAt, String updatedByAdminId) {
        public static PlatformFeeResponse from(co.subpilot.internal.fee.entity.PlatformFeeDefault d) {
            return new PlatformFeeResponse(d.getFeeBps(), d.getFixedFeeMinor(),
                    d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null, d.getUpdatedByAdminId());
        }
    }

    public record PlatformFeeUpdateRequest(
            @NotNull @Min(0) Integer feeBps,
            @NotNull @Min(0) Long fixedFeeMinor,
            @NotBlank String reason
    ) {}
}