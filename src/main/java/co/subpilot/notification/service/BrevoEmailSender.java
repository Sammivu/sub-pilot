package co.subpilot.notification.service;

import co.subpilot.notification.EmailProperties;
import co.subpilot.notification.service.EmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single EmailSender bean for the whole app — no conditional bean-selection
 * needed (and therefore no risk of two competing beans, which is what a
 * blank-but-present BREVO_API_KEY would otherwise trigger under
 * @ConditionalOnProperty's "any non-'false' value counts as present" rule).
 *
 * Behaviour is decided once, at send time, from the resolved config:
 *   - subpilot.email.enabled=false                  -> no-op, logs intent
 *   - subpilot.email.brevo-api-key blank/unset       -> logs intent (dev/CI fallback,
 *                                                        mirrors how MockNombaGateway
 *                                                        lets billing run without
 *                                                        real Nomba credentials)
 *   - otherwise                                      -> real call to Brevo's API
 *
 * Endpoint (developers.brevo.com/docs/send-a-transactional-email):
 *   POST https://api.brevo.com/v3/smtp/email
 *   Header: api-key: <BREVO_API_KEY>
 *   Body:   { sender: {name, email}, to: [{email, name}], subject, htmlContent }
 *   Response: { messageId: "<...>" } on success (HTTP 201)
 */
@Slf4j
@Component
public class BrevoEmailSender implements EmailSender {

    private final WebClient webClient;
    private final EmailProperties properties;

    public BrevoEmailSender(EmailProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBrevoBaseUrl())
                .build();
    }

    @Override
    public EmailSendResult send(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        if (!properties.isEnabled()) {
            log.info("[EMAIL] Sending disabled (subpilot.email.enabled=false) — skipping send to {}", toEmail);
            return EmailSendResult.success("disabled-noop");
        }

        if (properties.getBrevoApiKey() == null || properties.getBrevoApiKey().isBlank()) {
            return logOnly(toEmail, toName, subject, htmlBody, templateTag);
        }

        return sendViaBrevo(toEmail, toName, subject, htmlBody, templateTag);
    }

    private EmailSendResult sendViaBrevo(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("name", properties.getFromName());
        sender.put("email", properties.getFromEmail());

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("email", toEmail);
        if (toName != null && !toName.isBlank()) {
            recipient.put("name", toName);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", sender);
        body.put("to", List.of(recipient));
        body.put("subject", subject);
        body.put("htmlContent", htmlBody);
        if (templateTag != null) {
            body.put("tags", List.of(templateTag));
        }

        try {
            JsonNode response = webClient.post()
                    .uri("/smtp/email")
                    .header("api-key", properties.getBrevoApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(properties.getConnectTimeoutMs() + properties.getReadTimeoutMs()))
                    .block();

            String messageId = response != null ? response.path("messageId").asText(null) : null;
            log.info("[BREVO] Email sent — to={} template={} messageId={}", toEmail, templateTag, messageId);
            return EmailSendResult.success(messageId);

        } catch (WebClientResponseException e) {
            log.error("[BREVO] Send failed — to={} template={} status={} body={}",
                    toEmail, templateTag, e.getStatusCode(), e.getResponseBodyAsString());
            return EmailSendResult.failure("Brevo API error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[BREVO] Send failed — to={} template={}: {}", toEmail, templateTag, e.getMessage(), e);
            return EmailSendResult.failure("Brevo send failed: " + e.getMessage());
        }
    }

    /**
     * Dev/CI fallback when no Brevo API key is configured. Logs what would
     * have been sent and returns a synthetic success so callers (and the
     * NotificationLogs audit trail) behave identically whether or not a
     * real key is present — only the "did it actually leave the building"
     * fact differs, which is fine for local development and demos.
     */
    private EmailSender.EmailSendResult logOnly(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        String fakeMessageId = "mock_email_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[MOCK EMAIL — no Brevo API key configured] To: {} <{}> | Subject: {} | Template: {}",
                toName, toEmail, subject, templateTag);
        log.debug("[MOCK EMAIL] Body preview: {}", htmlBody.length() > 200 ? htmlBody.substring(0, 200) + "..." : htmlBody);
        return EmailSendResult.success(fakeMessageId);
    }
}