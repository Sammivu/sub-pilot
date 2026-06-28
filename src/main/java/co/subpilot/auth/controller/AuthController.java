package co.subpilot.auth.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.auth.dto.AuthDtos;
import co.subpilot.auth.service.AuthService;
import co.subpilot.common.tenant.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditLogService auditLogService;

    @PostMapping("/auth/signup")
    public ResponseEntity<AuthDtos.AuthResponse> signup(@Valid @RequestBody AuthDtos.SignupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(req));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
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
}