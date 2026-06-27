package co.subpilot.nomba.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenRequest(

        @JsonProperty("grant_type")
        String grantType,

        @JsonProperty("client_id")
        String clientId,

        @JsonProperty("client_secret")
        String clientSecret
) {
}