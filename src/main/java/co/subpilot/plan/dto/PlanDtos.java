package co.subpilot.plan.dto;

import co.subpilot.plan.BillingInterval;
import co.subpilot.plan.PlanStatus;
import co.subpilot.plan.ProrationPolicy;
import co.subpilot.plan.entity.Plan;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class PlanDtos {

    public record CreatePlanRequest(
            @NotBlank String name,
            String description,
            @Positive long amount,
            String currency, // defaults to NGN if null
            @NotNull BillingInterval billingInterval,
            Integer intervalValue,     // for custom
            String intervalUnit,       // for custom
            @Min(0) Integer trialDays,
            ProrationPolicy prorationPolicy
    ) {}

    public record UpdatePlanRequest(
            String name,
            String description,
            @Min(0) Integer trialDays
            // amount changes intentionally excluded — PRD §6.2: "amount changes apply to new subscriptions only"
            // handled via a separate plan-version mechanism if needed later
    ) {}

    public record PlanResponse(
            String id,
            String name,
            String slug,
            String description,
            long amount,
            String currency,
            BillingInterval billingInterval,
            int trialDays,
            ProrationPolicy prorationPolicy,
            PlanStatus status,
            String hostedUrl,
            String createdAt
    ) {
        public static PlanResponse from(Plan plan, String merchantSlug, String frontendBaseUrl) {
            String hostedUrl = frontendBaseUrl + "/plans/" + merchantSlug + "/" + plan.getSlug();
            return new PlanResponse(
                    plan.getId(), plan.getName(), plan.getSlug(), plan.getDescription(),
                    plan.getAmount(), plan.getCurrency(), plan.getBillingInterval(),
                    plan.getTrialDays(), plan.getProrationPolicy(), plan.getStatus(),
                    hostedUrl, plan.getCreatedAt().toString()
            );
        }
    }

    public record PublicPlanResponse(
            String name,
            String description,
            long amount,
            String currency,
            BillingInterval billingInterval,
            int trialDays,
            String merchantName,
            String merchantSlug,
            String planSlug
    ) {}
}