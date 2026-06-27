package co.subpilot.portal.dto;

import co.subpilot.invoice.entity.Invoice;
import co.subpilot.plan.entity.Plan;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Subscriber-facing DTOs for the customer self-service portal (PRD §6.7).
 *
 * Deliberately slimmer than the operator-console DTOs: a subscriber should
 * never see merchant_id, SubPilot's platform fee breakdown, internal proration
 * dedup keys, or any other operational/financial detail that's none of their
 * business. Everything here is a hand-picked projection, not the raw entity.
 */
public class PortalDtos {

    public record PortalSubscriptionView(
            String subscriptionId,
            SubscriptionStatus status,
            String planName,
            long planAmount,
            String currency,
            String billingInterval,
            String currentPeriodStart,
            String currentPeriodEnd,
            String nextBillingDate,
            String trialEndsAt,
            boolean cancelAtPeriodEnd,
            String cardLast4,      // null if not captured (see Customer entity note — Nomba card metadata isn't
            String cardBrand       // currently plumbed through the checkout-confirmation webhook)
    ) {
        public static PortalSubscriptionView from(Subscription sub, Plan plan, String cardLast4, String cardBrand) {
            return new PortalSubscriptionView(
                    sub.getId(), sub.getStatus(), plan.getName(), plan.getAmount(), plan.getCurrency(),
                    plan.getBillingInterval().name(),
                    sub.getCurrentPeriodStart() != null ? sub.getCurrentPeriodStart().toString() : null,
                    sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toString() : null,
                    sub.getNextBillingDate() != null ? sub.getNextBillingDate().toString() : null,
                    sub.getTrialEndsAt() != null ? sub.getTrialEndsAt().toString() : null,
                    sub.isCancelAtPeriodEnd(), cardLast4, cardBrand
            );
        }
    }

    public record PortalInvoiceView(
            String invoiceId,
            String invoiceNumber,
            long amount,
            String currency,
            String status,
            String dueDate,
            String paidAt,
            String periodStart,
            String periodEnd
    ) {
        public static PortalInvoiceView from(Invoice inv) {
            return new PortalInvoiceView(
                    inv.getId(), inv.getInvoiceNumber(), inv.getAmount(), inv.getCurrency(), inv.getStatus(),
                    inv.getDueDate() != null ? inv.getDueDate().toString() : null,
                    inv.getPaidAt() != null ? inv.getPaidAt().toString() : null,
                    inv.getPeriodStart() != null ? inv.getPeriodStart().toString() : null,
                    inv.getPeriodEnd() != null ? inv.getPeriodEnd().toString() : null
            );
        }
    }

    public record PortalCancelRequest(
            @Size(max = 500) String reason
    ) {}

    /** Returned from the "update payment method" action — a fresh Nomba checkout to re-tokenise a card. */
    public record PortalUpdateCardResponse(
            String checkoutUrl,
            String reference
    ) {}

    public record PortalAvailablePlan(
            String planId,
            String name,
            long amount,
            String currency,
            String billingInterval
    ) {}

    public record PortalChangePlanRequest(
            @NotBlank String newPlanId
    ) {}
}