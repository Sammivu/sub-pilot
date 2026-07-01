package co.subpilot.nomba.dto;

public record TokenizedChargeRequest(
        String tokenKey,
        ChargeOrder order
) {
}