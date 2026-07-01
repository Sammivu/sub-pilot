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

    private SessionCookie() {}
}