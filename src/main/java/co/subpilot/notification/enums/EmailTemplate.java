package co.subpilot.notification.enums;

/**
 * Canonical transactional email templates, matching PRD §6.9 exactly.
 *
 * Merchant notifications:
 *   NEW_SUBSCRIBER, PAYMENT_FAILED_MERCHANT, DUNNING_EXHAUSTED_MERCHANT, SUBSCRIPTION_CANCELLED_MERCHANT
 *
 * Subscriber notifications:
 *   SUBSCRIPTION_ACTIVATED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, DUNNING_WARNING, SUBSCRIPTION_CANCELLED
 *
 * The string value of each constant is also the "template" column value
 * persisted on NotificationLog and the Brevo tag attached to the send —
 * keep these stable since dashboards / filters may key off them.
 */
public enum EmailTemplate {

    // ── Subscriber-facing ───────────────────────────────────────────────────
    SUBSCRIPTION_ACTIVATED,
    PAYMENT_SUCCEEDED,
    PAYMENT_FAILED,
    DUNNING_WARNING,
    SUBSCRIPTION_SUSPENDED,
    SUBSCRIPTION_CANCELLED,

    // ── Merchant-facing ──────────────────────────────────────────────────────
    NEW_SUBSCRIBER,
    PAYMENT_FAILED_MERCHANT,
    DUNNING_EXHAUSTED_MERCHANT,
    SUBSCRIPTION_CANCELLED_MERCHANT
}