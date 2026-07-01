package co.subpilot.nomba.dto;

public record TokenizedChargeResponse(
        String code,
        String description,
        ChargeData data
) {
}