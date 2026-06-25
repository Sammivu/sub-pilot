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

    public record AuthResponse(
        String token,
        String merchantId,
        String userId,
        String email,
        String businessName
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