package co.subpilot.auth.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.auth.dto.AuthDtos;
import co.subpilot.auth.security.SessionCookie;
import co.subpilot.auth.service.AuthService;
import co.subpilot.common.tenant.TenantContext;
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

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditLogService auditLogService;

    @Value("${subpilot.auth.cookie-secure}")
    private boolean cookieSecure;

    @Value("${subpilot.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${subpilot.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @PostMapping("/auth/signup")
    public ResponseEntity<AuthDtos.AuthResponse> signup(@Valid @RequestBody AuthDtos.SignupRequest req) {
        AuthService.AuthResult result = authService.signup(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie().toString())
                .body(result.body());
    }

    /**
     * Item 1 — session bootstrap. Reads whatever AuthFilter already put in
     * the SecurityContext from the _subpilot_session cookie (see AuthFilter)
     * — no separate cookie parsing here, this endpoint is just the first one
     * that needed to expose "who did that authentication resolve to" back
     * to the caller as JSON. 401 with no body if there's no valid session,
     * exactly like every other protected route would behave, just made
     * explicit and named for the frontend's boot-time check.
     */
    @GetMapping("/auth/me")
    public ResponseEntity<AuthDtos.AuthResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth != null ? auth.getName() : null;

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(authService.getCurrentUser(userId));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        AuthService.AuthResult result = authService.login(req);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie().toString())
                .body(result.body());
    }

    /**
     * Item 2 — reads the refresh credential from the HttpOnly
     * _subpilot_refresh cookie (the "preferred fix" from the handoff doc),
     * with the request body kept as a fallback for any non-browser caller
     * that can't rely on cookies (e.g. a mobile client storing the refresh
     * token itself). Cookie wins if both are somehow present. Returns a
     * rotated pair of both cookies — see AuthService.refresh's rotation
     * note for why the old refresh token stops working the moment this
     * succeeds.
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthDtos.RefreshResponse> refresh(
            @CookieValue(name = SessionCookie.REFRESH_NAME, required = false) String refreshCookieValue,
            @RequestBody(required = false) AuthDtos.RefreshRequest req
    ) {
        String rawRefreshToken = refreshCookieValue != null ? refreshCookieValue
                : (req != null ? req.refreshToken() : null);

        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        AuthService.AuthResult result = authService.refresh(new AuthDtos.RefreshRequest(rawRefreshToken));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
                .header(HttpHeaders.SET_COOKIE, csrfCookie().toString())
                .body(new AuthDtos.RefreshResponse(result.refreshToken()));
    }

    /**
     * Item 3 — revokes via the refresh cookie rather than the current
     * SecurityContext, so logout works even when the access cookie already
     * expired (the exact gap the handoff doc called out: previously, an
     * expired access cookie meant the stored refresh token silently
     * survived logout and stayed exchangeable). Falls back to the old
     * SecurityContext-based revocation too, belt-and-suspenders, in case a
     * caller somehow has a valid access session but no refresh cookie (e.g.
     * it was manually deleted client-side) — either path revokes the same
     * underlying refresh_token_hash column, so there's no double-revoke
     * hazard.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, String>> logout(
            @CookieValue(name = SessionCookie.REFRESH_NAME, required = false) String refreshToken
    ) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logoutByRefreshToken(refreshToken);
        } else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null) {
                authService.logout(auth.getName());
            }
        }

        ResponseCookie expiredAccess = ResponseCookie.from(SessionCookie.NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        ResponseCookie expiredRefresh = ResponseCookie.from(SessionCookie.REFRESH_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        ResponseCookie expiredCsrf = ResponseCookie.from(SessionCookie.CSRF_NAME, "")
                .httpOnly(false)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredAccess.toString())
                .header(HttpHeaders.SET_COOKIE, expiredRefresh.toString())
                .header(HttpHeaders.SET_COOKIE, expiredCsrf.toString())
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

    /**
     * NOT HttpOnly — this is the "double" in double-submit-cookie. The
     * frontend must read this to send it back as X-CSRF-Token. Its
     * usefulness comes entirely from same-origin policy on document.cookie
     * (a third-party page can't read it), not from secrecy against the
     * legitimate frontend. See CsrfProtectionFilter for the verification
     * side and SessionCookie.CSRF_NAME's javadoc for the full rationale.
     */
    private ResponseCookie csrfCookie() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        return ResponseCookie.from(SessionCookie.CSRF_NAME, token)
                .httpOnly(false)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMillis(refreshExpirationMs)) // lives as long as the session can be refreshed
                .build();
    }

    private ResponseCookie sessionCookie(String accessToken) {
        return ResponseCookie.from(SessionCookie.NAME, accessToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .build();
    }

    /**
     * Path "/" (not scoped to /v1/auth/refresh) — deliberately broad so the
     * cookie round-trips on every request the same way _subpilot_session
     * does. A narrower path would mean the browser stops sending it on
     * non-auth routes, which is harmless for refresh itself but makes the
     * cookie inconsistent to reason about alongside the session cookie.
     */
    private ResponseCookie refreshCookie(String refreshToken) {
        return ResponseCookie.from(SessionCookie.REFRESH_NAME, refreshToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMillis(refreshExpirationMs))
                .build();
    }
}