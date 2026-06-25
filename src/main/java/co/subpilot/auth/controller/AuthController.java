package co.subpilot.auth.controller;

import co.subpilot.auth.dto.AuthDtos;
import co.subpilot.auth.service.AuthService;
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
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.createApiKey(req));
    }

    @GetMapping("/settings/api-keys")
    public ResponseEntity<List<AuthDtos.ApiKeyResponse>> listApiKeys() {
        return ResponseEntity.ok(authService.listApiKeys());
    }

    @DeleteMapping("/settings/api-keys/{keyId}")
    public ResponseEntity<Map<String, String>> revokeApiKey(@PathVariable String keyId) {
        authService.revokeApiKey(keyId);
        return ResponseEntity.ok(Map.of("message", "API key revoked successfully."));
    }
}