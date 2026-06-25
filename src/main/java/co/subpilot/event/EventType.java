package co.subpilot.event;

/**
 * Canonical event type strings.
 * Matches PRD §6.10 plus the frontend's BACKEND_HANDOFF.md webhook catalogue.
 */
public final class EventType {
 
    private EventType() {}
 
    // Merchant / tenant
    public static final String MERCHANT_CREATED = "MERCHANT_CREATED";
 
    // Plan
    public static final String PLAN_CREATED = "PLAN_CREATED";
    public static final String PLAN_UPDATED = "PLAN_UPDATED";
    public static final String PLAN_PUBLISHED = "PLAN_PUBLISHED";
    public static final String PLAN_ARCHIVED = "PLAN_ARCHIVED";
 
    // Subscription lifecycle
    public static final String SUBSCRIPTION_CREATED = "SUBSCRIPTION_CREATED";
    public static final String SUBSCRIPTION_ACTIVATED = "SUBSCRIPTION_ACTIVATED";
    public static final String SUBSCRIPTION_RENEWED = "SUBSCRIPTION_RENEWED";
    public static final String SUBSCRIPTION_PAUSED = "SUBSCRIPTION_PAUSED";
    public static final String SUBSCRIPTION_RESUMED = "SUBSCRIPTION_RESUMED";
    public static final String SUBSCRIPTION_CANCELLED = "SUBSCRIPTION_CANCELLED";
    public static final String SUBSCRIPTION_EXPIRED = "SUBSCRIPTION_EXPIRED";
    public static final String SUBSCRIPTION_PAST_DUE = "SUBSCRIPTION_PAST_DUE";
 
    // Payment
    public static final String PAYMENT_INITIATED = "PAYMENT_INITIATED";
    public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
 
    // Invoice
    public static final String INVOICE_CREATED = "INVOICE_CREATED";
    public static final String INVOICE_PAID = "INVOICE_PAID";
    public static final String INVOICE_VOIDED = "INVOICE_VOIDED";
 
    // Dunning
    public static final String DUNNING_STARTED = "DUNNING_STARTED";
    public static final String DUNNING_STEP_EXECUTED = "DUNNING_STEP_EXECUTED";
    public static final String DUNNING_RESOLVED = "DUNNING_RESOLVED";
    public static final String DUNNING_EXHAUSTED = "DUNNING_EXHAUSTED";
    public static final String DUNNING_RECOVERED = "DUNNING_RECOVERED";

    // Proration
    public static final String PRORATION_APPLIED = "PRORATION_APPLIED";
 
    // Webhook
    public static final String WEBHOOK_DELIVERED = "WEBHOOK_DELIVERED";
    public static final String WEBHOOK_FAILED = "WEBHOOK_FAILED";
 
    // Refund
    public static final String REFUND_CREATED = "REFUND_CREATED";
    public static final String REFUND_SUCCEEDED = "REFUND_SUCCEEDED";
    public static final String REFUND_FAILED = "REFUND_FAILED";
}
 