package co.subpilot.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fallback EmailSender active when no Brevo API key is configured
 * (subpilot.email.brevo-api-key is blank/unset).
 *
 * Logs what would have been sent instead of actually sending — lets the
 * app run end-to-end in local dev or CI without needing a real Brevo
 * account, mirroring how MockNombaGateway lets billing run without real
 * Nomba credentials.
 */
@Slf4j
@Component
//@ConditionalOnProperty(name = "subpilot.email.brevo-api-key", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    @Override
    public EmailSendResult send(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        String fakeMessageId = "mock_email_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[MOCK EMAIL] To: {} <{}> | Subject: {} | Template: {} | (no Brevo API key configured — not actually sent)",
                toName, toEmail, subject, templateTag);
        log.debug("[MOCK EMAIL] Body preview: {}", htmlBody.length() > 200 ? htmlBody.substring(0, 200) + "..." : htmlBody);
        return EmailSendResult.success(fakeMessageId);
    }
}