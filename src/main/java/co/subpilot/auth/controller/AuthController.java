package co.subpilot.auth.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.auth.dto.AuthDtos;
import co.subpilot.auth.service.AuthService;
import co.subpilot.common.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_COOKIE_NAME = "_subpilot_session";

    private final AuthService authService;
    private final AuditLogService auditLogService;

    @Value("${subpilot.auth.cookie-secure}")
    private boolean cookieSecure;

    @Value("${subpilot.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @PostMapping("/auth/signup")
    public ResponseEntity<AuthDtos.AuthResponse> signup(@Valid @RequestBody AuthDtos.SignupRequest req) {
        AuthService.AuthResult result = authService.signup(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.accessToken()).toString())
                .body(result.body());
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        AuthService.AuthResult result = authService.login(req);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.accessToken()).toString())
                .body(result.body());
    }

    /**
     * Gap 4 — exchanges a refresh token (sent in the request body, since it
     * is opaque and not itself sensitive the way an access token is — see
     * AuthService.issueRefreshToken's revocation rationale) for a new access
     * token, which goes out as the same HttpOnly cookie as login/signup,
     * plus a rotated refresh token in the response body.
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthDtos.RefreshResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        AuthService.AuthResult result = authService.refresh(req);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.accessToken()).toString())
                .body(new AuthDtos.RefreshResponse(result.refreshToken()));
    }

    /**
     * Gap 6 — expires the session cookie client-side (maxAge=0) AND
     * server-side (clears the stored refresh token hash via AuthService),
     * so a logged-out session can't be silently revived via /auth/refresh
     * either.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, String>> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            authService.logout(auth.getName());
        }

        ResponseCookie expired = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(Map.of("message", "Logged out."));
    }

    /** Gap 5 — validates the current password before replacing it. */
    @PatchMapping("/auth/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth != null ? auth.getName() : null;
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated."));
        }

        authService.changePassword(userId, req);
        auditLogService.record(TenantContext.requireMerchantId(), AuditAction.USER_PASSWORD_CHANGED,
                "user", userId, null, Map.of("changedAt", java.time.Instant.now().toString()));

        return ResponseEntity.ok(Map.of("message", "Password changed successfully."));
    }

    @PostMapping("/settings/api-keys")
    public ResponseEntity<AuthDtos.ApiKeyResponse> createApiKey(@Valid @RequestBody AuthDtos.CreateApiKeyRequest req) {
        AuthDtos.ApiKeyResponse created = authService.createApiKey(req);
        // Note: deliberately does NOT log the raw key itself into the audit
        // snapshot — only the label/id, which is all ApiKeyResponse exposes
        // besides the one-time raw key. Logging secrets into an audit trail
        // (even one that's supposedly internal-only) is a bad habit to start.
        auditLogService.recordCreation(TenantContext.requireMerchantId(), AuditAction.API_KEY_CREATED,
                "api_key", created.id(), Map.of("label", created.label()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/settings/api-keys")
    public ResponseEntity<List<AuthDtos.ApiKeyResponse>> listApiKeys() {
        return ResponseEntity.ok(authService.listApiKeys());
    }

    @DeleteMapping("/settings/api-keys/{keyId}")
    public ResponseEntity<Map<String, String>> revokeApiKey(@PathVariable String keyId) {
        authService.revokeApiKey(keyId);
        auditLogService.recordDeletion(TenantContext.requireMerchantId(), AuditAction.API_KEY_REVOKED,
                "api_key", keyId, Map.of("revoked", true));
        return ResponseEntity.ok(Map.of("message", "API key revoked successfully."));
    }

    private ResponseCookie sessionCookie(String accessToken) {
        return ResponseCookie.from(SESSION_COOKIE_NAME, accessToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .build();
    }
}