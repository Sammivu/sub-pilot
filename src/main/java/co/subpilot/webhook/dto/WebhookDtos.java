package co.subpilot.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request/response shapes for the webhook endpoint management API
 * (PRD §10 — POST/GET/DELETE /v1/webhooks/endpoints).
 */
public class WebhookDtos {

    public record RegisterEndpointRequest(
            @NotBlank String url,
            String description,
            @NotEmpty List<String> events // e.g. ["subscription.created", "payment.failed"]
    ) {}
}