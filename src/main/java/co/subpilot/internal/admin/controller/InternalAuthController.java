package co.subpilot.internal.admin.controller;

import co.subpilot.internal.admin.dto.InternalAdminDtos;
import co.subpilot.internal.admin.entity.InternalAdmin;
import co.subpilot.internal.admin.security.InternalSessionCookie;
import co.subpilot.internal.admin.service.InternalAdminAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Separate cookie contract from merchant auth — see InternalSessionCookie
 * and InternalAdminAuthFilter's javadocs for the full rationale.
 */
@RestController
@RequestMapping("/v1/internal/auth")
@RequiredArgsConstructor
public class InternalAuthController {

    private final InternalAdminAuthService authService;

    @Value("${subpilot.auth.cookie-secure}")
    private boolean cookieSecure;

    @Value("${subpilot.internal.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @PostMapping("/login")
    public ResponseEntity<InternalAdminDtos.MeResponse> login(@Valid @RequestBody InternalAdminDtos.LoginRequest req) {
        InternalAdminAuthService.LoginResult result = authService.login(req.email(), req.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(result.token()).toString())
                .body(InternalAdminDtos.MeResponse.from(result.admin()));
    }

    @GetMapping("/me")
    public ResponseEntity<InternalAdminDtos.MeResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String adminId = auth != null ? (String) auth.getPrincipal() : null;

        if (adminId == null) {
            return ResponseEntity.status(401).build();
        }

        InternalAdmin admin = authService.getById(adminId);
        return ResponseEntity.ok(InternalAdminDtos.MeResponse.from(admin));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        ResponseCookie expired = ResponseCookie.from(InternalSessionCookie.NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(InternalSessionCookie.PATH)
                .maxAge(0)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(Map.of("message", "Logged out."));
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(InternalSessionCookie.NAME, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path(InternalSessionCookie.PATH)
                .maxAge(Duration.ofMillis(jwtExpirationMs))
                .build();
    }
}