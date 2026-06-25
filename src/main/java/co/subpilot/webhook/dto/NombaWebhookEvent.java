package co.subpilot.webhook.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NombaWebhookEvent(

        @JsonProperty("event_type")
        String eventType,

        String requestId,

        NombaWebhookData data
) {
}