package co.subpilot.notification.service;

import java.util.Map;

/**
 * Abstraction over the transactional email provider.
 *
 * Mirrors the NombaPaymentGateway pattern: a real implementation
 * (BrevoEmailSender) and a no-op/logging implementation (LoggingEmailSender)
 * both implement this interface, selected via subpilot.email.enabled and
 * subpilot.notification.brevo-api-key being present. Callers (NotificationService)
 * never know which one is active.
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