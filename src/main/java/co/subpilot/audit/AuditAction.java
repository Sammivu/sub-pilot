package co.subpilot.audit;

/**
 * Canonical audit action strings — one per merchant-initiated mutation that
 * exists in the operator console / API. Dot-notation, matching the
 * resource.verb shape used throughout (mirrors the webhook event catalogue
 * convention in WebhookEventCatalogue).
 */
public final class AuditAction {

    // Plan
    public static final String PLAN_CREATED = "plan.created";
    public static final String PLAN_UPDATED = "plan.updated";
    public static final String PLAN_PUBLISHED = "plan.published";
    public static final String PLAN_ARCHIVED = "plan.archived";

    // Subscription
    public static final String SUBSCRIPTION_CANCELLED = "subscription.cancelled";
    public static final String SUBSCRIPTION_PAUSED = "subscription.paused";
    public static final String SUBSCRIPTION_RESUMED = "subscription.resumed";
    public static final String SUBSCRIPTION_PLAN_CHANGED = "subscription.plan_changed";

    // API keys
    public static final String API_KEY_CREATED = "api_key.created";
    public static final String API_KEY_REVOKED = "api_key.revoked";

    // Users / auth (Gap 5/6)
    public static final String USER_PASSWORD_CHANGED = "user.password_changed";

    // Webhook endpoints
    public static final String WEBHOOK_ENDPOINT_CREATED = "webhook_endpoint.created";
    public static final String WEBHOOK_ENDPOINT_DELETED = "webhook_endpoint.deleted";

    // Dunning configuration (PRD §8 — merchants can customise their campaign) — Gap 3
    public static final String DUNNING_CAMPAIGN_UPDATED = "dunning_campaign.updated";
    public static final String DUNNING_STEP_CREATED = "dunning_step.created";
    public static final String DUNNING_STEP_UPDATED = "dunning_step.updated";
    public static final String DUNNING_STEP_DELETED = "dunning_step.deleted";

    // Refunds — Gap 1
    public static final String REFUND_INITIATED = "refund.initiated";

    // Disbursements / payouts — Gap 2
    public static final String PAYOUT_TRIGGERED = "payout.triggered";

    private AuditAction() {}
}