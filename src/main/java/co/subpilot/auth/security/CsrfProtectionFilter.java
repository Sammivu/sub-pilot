package co.subpilot.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Item 4 — CSRF protection for cookie-authenticated merchant mutations.
 *
 * Only applies when ALL of these are true:
 *   1. The request is a state-changing method (POST/PUT/PATCH/DELETE)
 *   2. AuthFilter authenticated it via the _subpilot_session COOKIE (see
 *      AuthFilter.COOKIE_AUTH_ATTRIBUTE) — NOT via an Authorization header.
 *      API key / Bearer-JWT callers are structurally immune to CSRF (a
 *      cross-site page cannot make the victim's browser attach an
 *      Authorization header it doesn't know), so they're exempt.
 *   3. The path isn't one of the auth bootstrap endpoints (see EXEMPT_PATHS)
 *      — CSRF on signup/login is meaningless (no session exists yet to
 *      forge), and CSRF on refresh/logout only lets an attacker force a
 *      token rotation or a logout, neither of which crosses a trust
 *      boundary (no data read, no state mutated that benefits the
 *      attacker) — see rationale in AuthController.
 *
 * When it applies: requires the X-CSRF-Token header to exactly match the
 * (non-HttpOnly) _subpilot_csrf cookie value. A cross-site attacker's page
 * can trigger the browser into sending the session cookie, but cannot read
 * subpilot's cookies (same-origin policy on document.cookie) to forge a
 * matching header — that's the entire double-submit-cookie guarantee.
 */
@Slf4j
@Component
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String EXEMPT_PREFIX = "/v1/auth/";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {

        boolean cookieAuthenticated = Boolean.TRUE.equals(request.getAttribute(AuthFilter.COOKIE_AUTH_ATTRIBUTE));
        boolean mutating = MUTATING_METHODS.contains(request.getMethod());
        boolean exempt = request.getRequestURI().startsWith(EXEMPT_PREFIX);

        if (cookieAuthenticated && mutating && !exempt) {
            String headerToken = request.getHeader(SessionCookie.CSRF_HEADER);
            String cookieToken = readCsrfCookie(request);

            if (headerToken == null || cookieToken == null || !constantTimeEquals(headerToken, cookieToken)) {
                log.warn("CSRF check failed for {} {} — missing or mismatched {} header",
                        request.getMethod(), request.getRequestURI(), SessionCookie.CSRF_HEADER);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "CSRF token missing or invalid.");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String readCsrfCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (SessionCookie.CSRF_NAME.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /** Avoids a timing side-channel on token comparison — same reasoning as any secret comparison. */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}