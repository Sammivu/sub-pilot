package co.subpilot.dunning.dto;

import jakarta.validation.constraints.*;

import java.util.List;

/**
 * Request/response shapes for the dunning campaign configuration API (Gap 3).
 *
 * The Gap doc specifies exactly these endpoints:
 *   GET    /v1/dunning/campaigns
 *   GET    /v1/dunning/campaigns/{id}
 *   PATCH  /v1/dunning/campaigns/{id}
 *   POST   /v1/dunning/campaigns/{id}/steps
 *   PATCH  /v1/dunning/campaigns/{id}/steps/{stepId}
 *   DELETE /v1/dunning/campaigns/{id}/steps/{stepId}
 *
 * No new entities or migrations needed — DunningCampaign and DunningStep
 * tables already exist (V4 migration).
 */
public class DunningDtos {

    /**
     * Response for a campaign — includes its steps so the settings screen
     * can render the full retry schedule in one call.
     */
    public record CampaignResponse(
            String id,
            String name,
            int gracePeriodDays,
            int maxAttempts,
            boolean isDefault,
            boolean cancelAfterExhaustion,
            List<StepResponse> steps,
            String createdAt,
            String updatedAt
    ) {
        public static CampaignResponse from(co.subpilot.dunning.entity.DunningCampaign c,
                                            List<co.subpilot.dunning.entity.DunningStep> steps) {
            return new CampaignResponse(
                    c.getId(), c.getName(), c.getGracePeriodDays(), c.getMaxAttempts(),
                    Boolean.TRUE.equals(c.getIsDefault()),
                    Boolean.TRUE.equals(c.getCancelAfterExhaustion()),
                    steps.stream().map(StepResponse::from).toList(),
                    c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                    c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null
            );
        }
    }

    public record StepResponse(
            String id,
            int stepNumber,
            int dayOffset,
            String action,          // retry_charge | send_email | both
            String emailTemplate,   // payment_failed | final_warning | service_suspended | null
            String createdAt
    ) {}

    /**
     * PATCH /v1/dunning/campaigns/{id} — only the fields a merchant can
     * legitimately configure. merchantId and isDefault are internal and
     * are not accepted from the request body.
     */
    public record UpdateCampaignRequest(
            @Size(max = 100) String name,
            @Min(1) @Max(90) Integer gracePeriodDays,
            @Min(1) @Max(10) Integer maxAttempts,
            Boolean cancelAfterExhaustion
    ) {}

    /**
     * POST /v1/dunning/campaigns/{id}/steps — add a new step.
     * stepNumber is auto-assigned (max existing + 1) so the frontend
     * doesn't need to manage ordering manually.
     */
    public record CreateStepRequest(
            @NotNull @Min(0) @Max(90) Integer dayOffset,
            @NotBlank @Pattern(regexp = "retry_charge|send_email|both",
                    message = "action must be retry_charge, send_email, or both")
            String action,
            @Pattern(regexp = "payment_failed|final_warning|service_suspended",
                    message = "emailTemplate must be payment_failed, final_warning, or service_suspended")
            String emailTemplate  // null is valid when action is retry_charge
    ) {}

    /**
     * PATCH /v1/dunning/campaigns/{id}/steps/{stepId} — any subset of
     * mutable step fields. stepNumber is intentionally excluded —
     * reordering steps is managed by the dayOffset values themselves,
     * not by numbering, consistent with how DunningTriggerService iterates:
     * by scheduled time (failureStart + dayOffset), not by stepNumber.
     */
    public record UpdateStepRequest(
            @Min(0) @Max(90) Integer dayOffset,
            @Pattern(regexp = "retry_charge|send_email|both")
            String action,
            String emailTemplate
    ) {}
}