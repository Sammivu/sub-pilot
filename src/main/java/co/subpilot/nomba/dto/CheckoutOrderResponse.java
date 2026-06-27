package co.subpilot.nomba.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckoutOrderResponse(

        @JsonProperty("checkoutLink")
        String checkoutLink,

        String reference,

        String code,

        String description
) {
}