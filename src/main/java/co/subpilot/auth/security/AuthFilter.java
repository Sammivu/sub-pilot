package co.subpilot.auth.security;

import co.subpilot.auth.entity.ApiKey;
import co.subpilot.auth.repository.ApiKeyRepository;
import co.subpilot.common.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

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

    private final JwtService jwtService;
    private final ApiKeyRepository apiKeyRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);

                if (token.startsWith("sk_")) {
                    // API key auth
                    authenticateApiKey(token);
                } else {
                    // JWT auth
                    authenticateJwt(token);
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