package co.subpilot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record SignupRequest(
            @NotBlank @Size(min = 2, max = 255) String businessName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    /**
     * No longer carries the JWT (Gap 6 — security-critical). The access
     * token is now set as an HttpOnly _subpilot_session cookie by
     * AuthController, never exposed to JS / response body / localStorage.
     */
    public record AuthResponse(
            String merchantId,
            String userId,
            String email,
            String businessName
    ) {}

    /** Gap 4 — refresh token exchange. Like AuthResponse, never returns the access token in the body. */
    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}

    public record RefreshResponse(
            String refreshToken
    ) {}

    /** Gap 5 — password change. */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 100) String newPassword
    ) {}

    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 255) String label
    ) {}

    public record ApiKeyResponse(
            String id,
            String label,
            String prefix,
            String rawKey,   // Only populated on creation, never again
            String createdAt,
            String lastUsedAt,
            boolean active
    ) {}
}