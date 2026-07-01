package co.subpilot.auth.security;

/**
 * The HttpOnly cookie name used for dashboard sessions (Gap 6).
 *
 * Declared once here rather than as a literal string duplicated in both
 * AuthController (which sets it) and AuthFilter (which reads it) — a typo
 * in one place but not the other would silently break every dashboard
 * session with no compile-time warning.
 */
public final class SessionCookie {
    public static final String NAME = "_subpilot_session";

    /** Phase B handoff item 2 — HttpOnly refresh cookie, paired with NAME above. */
    public static final String REFRESH_NAME = "_subpilot_refresh";

    /**
     * Item 4 — double-submit CSRF cookie. Deliberately NOT HttpOnly: the
     * frontend JS must be able to read it to echo it back as the
     * X-CSRF-Token header. This is safe precisely because it's readable —
     * a cross-site attacker's page can't read cookies set by subpilot.co's
     * origin (same-origin policy on document.cookie), so it can't forge a
     * matching header even though the browser will auto-attach the actual
     * session cookie to a forged request. See CsrfProtectionFilter.
     */
    public static final String CSRF_NAME = "_subpilot_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private SessionCookie() {}
}