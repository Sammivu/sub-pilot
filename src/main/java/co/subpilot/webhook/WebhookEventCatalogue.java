package co.subpilot.webhook;

/**
 * Maps internal EventType strings to the public webhook event names exposed
 * to downstream developers. Matches frontend BACKEND_HANDOFF.md catalogue
 * exactly (dot-notation, not the internal SCREAMING_SNAKE_CASE).
 */
public final class WebhookEventCatalogue {

    private WebhookEventCatalogue() {}

    public static String toPublicEventName(String internalType) {
        return switch (internalType) {
            case "PLAN_CREATED" -> "plan.created";
            case "PLAN_PUBLISHED" -> "plan.published";
            case "SUBSCRIPTION_CREATED" -> "subscription.created";
            case "SUBSCRIPTION_ACTIVATED" -> "subscription.activated";
            case "SUBSCRIPTION_PAST_DUE" -> "subscription.past_due";
            case "SUBSCRIPTION_SUSPENDED" -> "subscription.suspended";
            case "SUBSCRIPTION_PAUSED" -> "subscription.paused";
            case "SUBSCRIPTION_RESUMED" -> "subscription.resumed";
            case "SUBSCRIPTION_CANCELLED" -> "subscription.cancelled";
            case "SUBSCRIPTION_EXPIRED" -> "subscription.expired";
            case "INVOICE_CREATED" -> "invoice.created";
            case "INVOICE_PAID" -> "invoice.paid";
            case "PAYMENT_FAILED" -> "payment.failed"; // also used for invoice.payment_failed semantics
            case "PAYMENT_SUCCEEDED" -> "payment_attempt.succeeded";
            case "DUNNING_STARTED" -> "dunning.started";
            case "DUNNING_RESOLVED" -> "dunning.recovered";
            case "DUNNING_EXHAUSTED" -> "dunning.exhausted";
            case "REFUND_CREATED" -> "refund.created";
            case "REFUND_SUCCEEDED" -> "refund.succeeded";
            case "REFUND_FAILED" -> "refund.failed";
            case "PAYOUT_TRIGGERED" -> "payout.triggered";
            case "PAYOUT_SUCCEEDED" -> "payout.succeeded";
            case "PAYOUT_FAILED" -> "payout.failed";
            default -> internalType.toLowerCase().replace('_', '.');
        };
    }
}