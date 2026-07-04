package co.subpilot.subscription.dto;

import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SubscriptionDtos {

    public record CreateSubscriptionRequest(
            @NotBlank String planId,
            @NotBlank String customerName,
            @NotBlank @Email String customerEmail,
            String customerPhone
    ) {}

    /**
     * Used by the public hosted checkout flow: /v1/public/plans/{merchantSlug}/{planSlug}/checkout
     */
    public record CheckoutRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Please provide a valid email address")
            @Size(max = 255, message = "Email cannot exceed 255 characters")
            String email,
            @NotBlank(message = "Full name is required")
            @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
            String fullName,
            @Pattern(regexp = "^[+\\d\\s()\\-]{8,20}$", message = "Please provide a valid phone number")
            String phone,
//            @NotBlank(message = "Country code is required NG, GB, US")
//            String countryCode,
            String merchantSlug,
            String planSlug
    ) {}

    public record CheckoutInitResponse(
            String subscriptionId,
            String checkoutUrl,
            String checkoutReference
    ) {}

    /**
     * immediate=true cancels right away; immediate=false sets cancel_at_period_end.
     */
    public record CancelRequest(
            String reason,
            boolean immediate
    ) {}

    public record ChangePlanRequest(
            @NotBlank String newPlanId
    ) {}

    public record SubscriptionResponse(
            String id,
            String customerId,
            String planId,
            SubscriptionStatus status,
            String currentPeriodStart,
            String currentPeriodEnd,
            String nextBillingDate,
            String trialEndsAt,
            boolean cancelAtPeriodEnd,
            String subscriptionToken
    ) {
        public static SubscriptionResponse from(Subscription s) {
            return new SubscriptionResponse(
                    s.getId(), s.getCustomerId(), s.getPlanId(), s.getStatus(),
                    s.getCurrentPeriodStart() != null ? s.getCurrentPeriodStart().toString() : null,
                    s.getCurrentPeriodEnd() != null ? s.getCurrentPeriodEnd().toString() : null,
                    s.getNextBillingDate() != null ? s.getNextBillingDate().toString() : null,
                    s.getTrialEndsAt() != null ? s.getTrialEndsAt().toString() : null,
                    s.isCancelAtPeriodEnd(),
                    s.getSubscriptionToken()
            );
        }
    }
}

