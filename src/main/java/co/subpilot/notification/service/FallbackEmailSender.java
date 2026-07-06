package co.subpilot.notification.service;

import co.subpilot.notification.EmailProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The single EmailSender bean actually injected everywhere (EmailDispatcher,
 * InternalAdminNotificationService) — @Primary disambiguates it against the
 * two concrete provider beans (ResendEmailSender, BrevoEmailSender), which
 * also implement EmailSender but are never injected by interface type
 * directly; they're wired into this class by their CONCRETE type instead,
 * which Spring resolves unambiguously regardless of how many beans
 * implement the shared interface.
 *
 * Resend is primary, Brevo is the automatic fallback — but ONLY a real
 * Resend failure triggers that fallback. If resendApiKey isn't configured,
 * ResendEmailSender.send() returns its own log-only "success" (a dev/CI
 * convenience — see its javadoc), and treating that mock success as a real
 * one here would mean a not-yet-configured Resend key silently swallows
 * every email into log-only mode forever, even with a perfectly working
 * Brevo key sitting right there unused. So the blank-key check happens
 * here, before ever calling Resend, not inside the result-handling below.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class FallbackEmailSender implements EmailSender {

    private final ResendEmailSender resend;
    private final BrevoEmailSender brevo;
    private final EmailProperties properties;

    @Override
    public EmailSendResult send(String toEmail, String toName, String subject, String htmlBody, String templateTag) {
        if (properties.getResendApiKey() == null || properties.getResendApiKey().isBlank()) {
            log.debug("[EMAIL] No Resend API key configured — going straight to Brevo for to={} template={}", toEmail, templateTag);
            return brevo.send(toEmail, toName, subject, htmlBody, templateTag);
        }

        EmailSendResult result = resend.send(toEmail, toName, subject, htmlBody, templateTag);
        if (result.success()) {
            return result;
        }

        log.warn("[EMAIL] Resend failed for to={} template={} ({}), falling back to Brevo",
                toEmail, templateTag, result.errorMessage());
        return brevo.send(toEmail, toName, subject, htmlBody, templateTag);
    }
}