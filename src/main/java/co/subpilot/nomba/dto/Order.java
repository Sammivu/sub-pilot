package co.subpilot.nomba.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)

public record Order(
        String amount,
        String currency,
        String callbackUrl,
        String customerEmail,
        String customerName,
        String customerPhone,
        String reference
) {
}