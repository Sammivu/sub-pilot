package co.subpilot.internal.admin.security;

public final class InternalSessionCookie {
    public static final String NAME = "_subpilot_internal_session";

    /**
     * Real internal routes live under /v1/internal — Path here MUST match
     * that prefix (not just "/internal") or the browser will never
     * actually attach this cookie to real requests. This is the
     * defense-in-depth layer from the spec: even if server-side checks
     * somehow had a bug, the browser itself won't send this cookie to
     * /v1/plans, /v1/subscriptions, etc.
     */
    public static final String PATH = "/v1/internal";

    private InternalSessionCookie() {}
}