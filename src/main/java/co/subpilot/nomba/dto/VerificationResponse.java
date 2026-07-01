package co.subpilot.nomba.dto;

public record VerificationResponse(
        boolean success,
        String reference,
        String status
) {
}