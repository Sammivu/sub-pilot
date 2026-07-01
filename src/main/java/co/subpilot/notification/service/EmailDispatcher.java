package co.subpilot.notification.service;


import co.subpilot.notification.EmailTemplateRenderer;
import co.subpilot.notification.enums.EmailTemplate;
import co.subpilot.notification.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Performs the actual email send and updates the NotificationLog row with
 * the outcome.
 *
 * Deliberately a separate bean from NotificationService: Spring's @Async
 * and @Transactional both work via proxies, and a proxy only intercepts
 * calls that come from OUTSIDE the bean. NotificationService calling its
 * own @Async method (self-invocation) would silently run synchronously on
 * the caller's thread instead — this split avoids that trap entirely by
 * making the hand-off a real cross-bean call.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatcher {

    private final EmailSender emailSender;
    private final NotificationLogRepository notificationLogRepository;

    @Async("asyncExecutor")
    public void dispatchAsync(String logId, String toEmail, String toName,
                              EmailTemplate template, Map<String, String> vars) {
        String subject = subjectFor(template);
        String html = EmailTemplateRenderer.render(template, vars);

        EmailSender.EmailSendResult result = emailSender.send(toEmail, toName, subject, html, template.name());

        updateLogResult(logId, result);
    }

    @Transactional
    public void updateLogResult(String logId, EmailSender.EmailSendResult result) {
        notificationLogRepository.findById(logId).ifPresent(logRow -> {
            if (result.success()) {
                logRow.setStatus("sent");
                logRow.setProviderMessageId(result.providerMessageId());
                logRow.setSentAt(Instant.now());
            } else {
                logRow.setStatus("failed");
                logRow.setErrorMessage(result.errorMessage());
                log.warn("Email send failed for log={}: {}", logId, result.errorMessage());
            }
            notificationLogRepository.save(logRow);
        });
    }

    private String subjectFor(EmailTemplate template) {
        return switch (template) {
            case SUBSCRIPTION_ACTIVATED -> "You're subscribed!";
            case PAYMENT_SUCCEEDED -> "Payment received";
            case PAYMENT_FAILED -> "Payment failed — action needed";
            case DUNNING_WARNING -> "Your subscription will be suspended soon";
            case SUBSCRIPTION_SUSPENDED -> "Your subscription has been suspended";
            case SUBSCRIPTION_CANCELLED -> "Your subscription has been cancelled";
            case NEW_SUBSCRIBER -> "New subscriber!";
            case PAYMENT_FAILED_MERCHANT -> "A subscriber's payment failed";
            case DUNNING_EXHAUSTED_MERCHANT -> "Subscriber payment recovery failed";
            case SUBSCRIPTION_CANCELLED_MERCHANT -> "A subscription was cancelled";
        };
    }
}