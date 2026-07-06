package co.subpilot.notification.service;

import java.util.Map;

/**
 * Abstraction over the transactional email provider.
 *
 * FallbackEmailSender is the bean actually injected (marked @Primary) —
 * it tries ResendEmailSender first, falling back to BrevoEmailSender on
 * any failure. Both providers independently fall back to logging the
 * intended send when their respective API key is unset/blank, so the
 * whole chain degrades gracefully in dev/CI with zero real credentials
 * configured. See FallbackEmailSender for the actual priority/fallback
 * wiring.
 */
public interface EmailSender {

    /**
     * Sends a single transactional email.
     *
     * @param toEmail      recipient address
     * @param toName       recipient display name (used in the To: header where supported)
     * @param subject      email subject line
     * @param htmlBody     fully-rendered HTML body (NotificationService owns template rendering)
     * @param templateTag  a short identifier (e.g. "payment_failed") attached as a Brevo tag for filtering/analytics
     * @return the result of the send attempt
     */
    EmailSendResult send(String toEmail, String toName, String subject, String htmlBody, String templateTag);

    record EmailSendResult(
            boolean success,
            String providerMessageId,
            String errorMessage
    ) {
        public static EmailSendResult success(String messageId) {
            return new EmailSendResult(true, messageId, null);
        }

        public static EmailSendResult failure(String errorMessage) {
            return new EmailSendResult(false, null, errorMessage);
        }
    }
}