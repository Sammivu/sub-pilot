package co.subpilot.internal.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class InternalAdminAuthDtos {

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}

    public record MeResponse(String adminId, String email, String role, String displayName) {
        public static MeResponse from(co.subpilot.internal.admin.entity.InternalAdmin admin) {
            return new MeResponse(admin.getId(), admin.getEmail(), admin.getRole(), admin.getDisplayName());
        }
    }
}