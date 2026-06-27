package co.subpilot.nomba.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RefundCheckoutResponse(
        String code,
        String description,
        RefundData data
) {
    public boolean isSuccessEnvelope() {
        return "00".equals(code);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundData(
            String refundReference,
            String status
    ) {}
}