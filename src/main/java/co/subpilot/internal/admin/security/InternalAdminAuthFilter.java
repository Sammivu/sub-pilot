package co.subpilot.internal.admin.security;

import co.subpilot.auth.security.SessionCookie;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.List;

import static co.subpilot.auth.security.AuthFilter.COOKIE_AUTH_ATTRIBUTE;

/**
 * Structurally separate from AuthFilter (merchant auth) — see
 * InternalAdminContext's javadoc for why this matters. Two independent
 * guarantees make cross-authentication impossible, not just avoided by
 * convention:
 *   1. This filter only ever runs its cookie-check for /v1/internal/**
 *      paths — AuthFilter is symmetrically excluded from that same prefix
 *      (see AuthFilter.doFilterInternal's early-return).
 *   2. Even if a merchant's _subpilot_session cookie were somehow present
 *      on an /v1/internal/** request, this filter only ever looks for
 *      _subpilot_internal_session by name — it wouldn't even attempt to
 *      read the merchant cookie, let alone verify it with this service's
 *      independent signing key.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalAdminAuthFilter extends OncePerRequestFilter {

    private final InternalAdminJwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // getServletPath(), NOT getRequestURI() — same context-path fix as
        // AuthFilter/CsrfProtectionFilter. Without this, this filter never
        // actually activated in production at all (server.servlet.context-path=/api
        // meant real requests were /api/v1/internal/... and this check for
        // a literal "/v1/internal/" prefix against the full URI never matched).
        if (!request.getServletPath().startsWith("/v1/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // Dashboard sessions authenticate via the HttpOnly
            // _subpilot_session cookie. We look up the cookie by name
            // directly rather than iterating over all cookies, which
            // remains safe even if multiple session cookies now use Path=/.
//            Cookie sessionCookie = WebUtils.getCookie(request, InternalSessionCookie.NAME);
//
//            if (sessionCookie != null) {
//                authenticate(sessionCookie.getValue());
//                if (SecurityContextHolder.getContext().getAuthentication() != null) {
//                    request.setAttribute(COOKIE_AUTH_ATTRIBUTE, true);
//                }
//            }

            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (InternalSessionCookie.NAME.equals(cookie.getName())) {
                        authenticate(cookie.getValue());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Internal admin auth filter error: {}", e.getMessage());
            InternalAdminContext.clear();
        }

        try {
            chain.doFilter(request, response);
        } finally {
            InternalAdminContext.clear();
        }
    }

    private void authenticate(String token) {
        if (!jwtService.isValid(token)) return;

        Claims claims = jwtService.validateAndExtract(token);
        String adminId = claims.getSubject();
        String role = claims.get("role", String.class);
        String email = claims.get("email", String.class);

        InternalAdminContext.set(adminId, role, email);

        var auth = new UsernamePasswordAuthenticationToken(
                adminId, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_" + role.toUpperCase())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}