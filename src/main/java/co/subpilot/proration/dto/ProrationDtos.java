package co.subpilot.proration.dto;

import jakarta.validation.constraints.NotBlank;

public class ProrationDtos {

    public record ChangePlanRequest(
            @NotBlank String newPlanId
    ) {}

    public record ChangePlanResponse(
            String subscriptionId,
            String previousPlanId,
            String newPlanId,
            long cycleDays,
            long unusedDays,
            long creditAmount,
            long newPlanProrated,
            long netChargeToday,
            long netCreditForward,
            boolean chargedImmediately,
            boolean takesEffectNextCycle,
            String paymentStatus // "charged" | "credited_forward" | "deferred_to_next_cycle" | "charge_failed"
    ) {}
}