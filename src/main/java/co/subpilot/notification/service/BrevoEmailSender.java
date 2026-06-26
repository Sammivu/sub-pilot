package co.subpilot.notification;

import co.subpilot.notification.service.EmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends transactional email via Brevo's REST API.
 *
 * This is the ONLY EmailSender bean registered — there is deliberately no
 * separate "mock" bean selected via @ConditionalOnProperty, because that
 * pattern is fragile here: Spring's @ConditionalOnProperty treats any
 * non-"false" value (including an empty string left over from an unset
 * .env placeholder) as "present", so a blank BREVO_API_KEY would satisfy
 * both a "key present" and a "key absent" condition simultaneously and
 * Spring would refuse to start with a duplicate-bean error.
 *
 * Instead, this single bean checks isConfigured() at call time and
 * degrades to a logging-only no-op when no real API key is set — the same
 * outcome as a separate mock bean would give, without the fragility.
 * This mirrors MockNombaGateway's purpose (let the app run end-to-end
 * without real credentials) but avoids duplicating the NombaPaymentGateway
 * two-bean pattern, which only works there because mock-mode is an
 * explicit boolean flag, not "is this string blank".
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

    private boolean isConfigured() {
        return properties.isEnabled()
                && properties.getBrevoApiKey() != null
                && !properties.getBrevoApiKey().isBlank();
    }

    @Override
    public EmailSender.EmailSendResult send(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        if (!properties.isEnabled()) {
            log.info("[EMAIL] Sending disabled (subpilot.email.enabled=false) — skipping send to {}", toEmail);
            return EmailSendResult.success("disabled-noop");
        }

        if (!isConfigured()) {
            String fakeMessageId = "mock_email_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            log.info("[MOCK EMAIL — no Brevo API key configured] To: {} <{}> | Subject: {} | Template: {}",
                    toName, toEmail, subject, templateTag);
            log.debug("[MOCK EMAIL] Body preview: {}",
                    htmlBody.length() > 200 ? htmlBody.substring(0, 200) + "..." : htmlBody);
            return EmailSendResult.success(fakeMessageId);
        }

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
}