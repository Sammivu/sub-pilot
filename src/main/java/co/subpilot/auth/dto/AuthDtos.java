package co.subpilot.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record SignupRequest(
            @NotBlank(message = "Business name is required.")
            @Size(min = 2, max = 255, message = "Business name must be between 2 and 255 characters.")
            String businessName,
            @NotBlank(message = "Email address is required.")
            @Email(message = "Please provide a valid email address.")
            @Size(max = 255, message = "Email address is too long.")
            String email,

            @NotBlank(message = "Password is required.")
            @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,100}$",
                    message = "Password must contain at least one uppercase letter, one lowercase letter, one number, and be at least 8 characters long."
            )
            String password
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