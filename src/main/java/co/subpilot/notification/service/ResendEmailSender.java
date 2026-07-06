package co.subpilot.notification.service;

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

import co.subpilot.notification.EmailProperties;

/**
 * Endpoint (resend.com/docs/api-reference/emails/send-email):
 *   POST https://api.resend.com/emails
 *   Header: Authorization: Bearer <RESEND_API_KEY>
 *   Body:   { from: "Name <email>", to: [email], subject, html }
 *   Response: { id: "<...>" } on success (HTTP 200)
 *
 * Structurally mirrors BrevoEmailSender exactly (same enabled/blank-key
 * fallback behavior) — see FallbackEmailSender for how the two combine
 * into the single EmailSender bean actually injected elsewhere.
 */
@Slf4j
@Component
public class ResendEmailSender implements EmailSender {

    private final WebClient webClient;
    private final EmailProperties properties;

    public ResendEmailSender(EmailProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getResendBaseUrl())
                .build();
    }

    @Override
    public EmailSendResult send(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        if (!properties.isEnabled()) {
            log.info("[EMAIL] Sending disabled (subpilot.email.enabled=false) — skipping send to {}", toEmail);
            return EmailSendResult.success("disabled-noop");
        }

        if (properties.getResendApiKey() == null || properties.getResendApiKey().isBlank()) {
            return logOnly(toEmail, toName, subject, htmlBody, templateTag);
        }

        return sendViaResend(toEmail, toName, subject, htmlBody, templateTag);
    }

    private EmailSendResult sendViaResend(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        // Resend's "to" field accepts either bare addresses or
        // "Name <email>" form — using the display-name form when we have
        // one, same as Brevo's separate name/email pair.
        String toField = (toName != null && !toName.isBlank())
                ? toName + " <" + toEmail + ">"
                : toEmail;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", properties.getFromName() + " <" + properties.getFromEmail() + ">");
        body.put("to", List.of(toField));
        body.put("subject", subject);
        body.put("html", htmlBody);
        if (templateTag != null) {
            // Resend's tags are {name, value} objects, not bare strings
            // like Brevo's — name must be alphanumeric/underscore/dash only
            // per their docs, so templateTag values already used elsewhere
            // (e.g. "payment_failed") are compatible as-is.
            body.put("tags", List.of(Map.of("name", "template", "value", templateTag)));
        }

        try {
            JsonNode response = webClient.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + properties.getResendApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(properties.getConnectTimeoutMs() + properties.getReadTimeoutMs()))
                    .block();

            String messageId = response != null ? response.path("id").asText(null) : null;
            log.info("[RESEND] Email sent — to={} template={} id={}", toEmail, templateTag, messageId);
            return EmailSendResult.success(messageId);

        } catch (WebClientResponseException e) {
            log.error("[RESEND] Send failed — to={} template={} status={} body={}",
                    toEmail, templateTag, e.getStatusCode(), e.getResponseBodyAsString());
            return EmailSendResult.failure("Resend API error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("[RESEND] Send failed — to={} template={}: {}", toEmail, templateTag, e.getMessage(), e);
            return EmailSendResult.failure("Resend send failed: " + e.getMessage());
        }
    }

    private EmailSendResult logOnly(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        String fakeMessageId = "mock_email_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[MOCK EMAIL — no Resend API key configured] To: {} <{}> | Subject: {} | Template: {}",
                toName, toEmail, subject, templateTag);
        log.debug("[MOCK EMAIL] Body preview: {}", htmlBody.length() > 200 ? htmlBody.substring(0, 200) + "..." : htmlBody);
        return EmailSendResult.success(fakeMessageId);
    }
}