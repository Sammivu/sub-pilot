package co.subpilot.audit;

/**
 * Matches the granted authority names AuthFilter assigns —
 * ROLE_MERCHANT -> a User acting via the dashboard, ROLE_API_KEY -> a
 * downstream developer's API key.
 */
public final class AuditActorType {
    public static final String USER = "user";
    public static final String API_KEY = "api_key";

    private AuditActorType() {}
}