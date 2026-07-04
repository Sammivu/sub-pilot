package co.subpilot.auth.security;

import co.subpilot.auth.entity.ApiKey;
import co.subpilot.auth.repository.ApiKeyRepository;
import co.subpilot.common.tenant.TenantContext;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Processes Authorization: Bearer <token> on every request.
 *
 * Accepts two token types:
 *   1. JWT  — issued by /auth/login, used by the merchant dashboard
 *   2. API key — issued by /settings/api-keys, used by downstream developers
 *
 * On success: sets SecurityContext + TenantContext.merchantId
 * On failure: continues chain with no auth (protected routes return 401 via Spring Security)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    /** Item 4 — set when a request authenticated via the _subpilot_session cookie, read by CsrfProtectionFilter. Never set for API key / raw-header JWT auth, which are not CSRF-vulnerable. */
    public static final String COOKIE_AUTH_ATTRIBUTE = "subpilot.cookieAuth";

    private final JwtService jwtService;
    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Symmetric with InternalAdminAuthFilter's own guard — see its
        // javadoc. Merchant auth must never even attempt to authenticate
        // an /v1/internal/** request, regardless of what cookies happen
        // to be present.
        // getServletPath(), NOT getRequestURI() — see CsrfProtectionFilter's
        // comment on the same fix. Without this, this exclusion silently
        // never matched in any deployment with server.servlet.context-path
        // set (e.g. "/api" in production), meaning AuthFilter was actually
        // attempting to authenticate /v1/internal/** requests too.
        if (request.getServletPath().startsWith("/v1/internal/")) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // Dashboard sessions authenticate via the HttpOnly
            // _subpilot_session cookie. We look up the cookie by name
            // directly rather than iterating over all cookies, which
            // remains safe even if multiple session cookies now use Path=/.
//            Cookie sessionCookie = WebUtils.getCookie(request, SessionCookie.NAME);
//
//            if (sessionCookie != null) {
//                authenticateJwt(sessionCookie.getValue());
//                if (SecurityContextHolder.getContext().getAuthentication() != null) {
//                    request.setAttribute(COOKIE_AUTH_ATTRIBUTE, true);
//                }
//            }

            // Gap 6 — dashboard sessions authenticate via the HttpOnly
            // _subpilot_session cookie. Checked first so a browser session
            // never needs to send an Authorization header at all. The
            // Authorization: Bearer header path below remains exactly as it
            // was for API key callers — completely unaffected by this change.
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (SessionCookie.NAME.equals(cookie.getName())) {
                        authenticateJwt(cookie.getValue());
                        if (SecurityContextHolder.getContext().getAuthentication() != null) {
                            request.setAttribute(COOKIE_AUTH_ATTRIBUTE, true);
                        }
                        break;
                    }
                }
            }

            // Fall through to the Authorization header only if the cookie
            // path above didn't already authenticate the request — this
            // covers both "no cookie present" (API key callers) and "cookie
            // present but invalid" (authenticateJwt no-ops on an invalid
            // token, so SecurityContext is still empty here).
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String header = request.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    String token = header.substring(7);

                    if (token.startsWith("sk_")) {
                        // API key auth
                        authenticateApiKey(token);
                    } else {
                        // JWT auth — e.g. a non-browser client sending the
                        // access token directly rather than via cookie
                        authenticateJwt(token);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Auth filter error: {}", e.getMessage());
            TenantContext.clear();
        }

        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void authenticateJwt(String token) {
        if (!jwtService.isValid(token)) return;

        Claims claims = jwtService.validateAndExtract(token);
        String userId = claims.getSubject();
        String merchantId = claims.get("merchantId", String.class);

        TenantContext.setMerchantId(merchantId);

        var auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        auth.setDetails(merchantId);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateApiKey(String rawKey) {
        String hash = sha256(rawKey);
        Optional<ApiKey> keyOpt = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(hash);

        if (keyOpt.isEmpty()) return;

        ApiKey apiKey = keyOpt.get();
        apiKeyRepository.updateLastUsed(apiKey.getId(), Instant.now());

        TenantContext.setMerchantId(apiKey.getMerchantId());

        var auth = new UsernamePasswordAuthenticationToken(
                apiKey.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
        auth.setDetails(apiKey.getMerchantId());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}