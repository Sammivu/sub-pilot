package co.subpilot.nomba.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeOrder(

        String orderReference,

        String customerEmail,

        String amount,

        String currency,

        String callbackUrl
) {
}