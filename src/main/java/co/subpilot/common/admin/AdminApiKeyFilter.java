package co.subpilot.common.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * Pragmatic MVP guard for /v1/admin/** — there is no platform-staff user
 * model in this codebase (every User belongs to exactly one merchant
 * tenant; see RefundService's class javadoc), so this checks a single
 * static shared secret rather than real cross-merchant RBAC. Good enough
 * to stop an arbitrary merchant hitting another merchant's refund queue,
 * not good enough for a team of actual SubPilot staff with individual
 * accountability — graduate this to real admin accounts before that
 * becomes a real requirement.
 */
@Slf4j
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

    @Value("${subpilot.admin.api-key:}")
    private String adminApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/v1/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        if (adminApiKey == null || adminApiKey.isBlank()) {
            log.error("subpilot.admin.api-key is not configured — refusing all admin requests");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Admin endpoints not configured.");
            return;
        }

        String provided = request.getHeader("X-Admin-Key");
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(), adminApiKey.getBytes())) {
            log.warn("Rejected admin request to {} — missing or invalid X-Admin-Key", request.getRequestURI());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or missing admin key.");
            return;
        }

        chain.doFilter(request, response);
    }
}