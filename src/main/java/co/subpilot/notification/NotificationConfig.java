package co.subpilot.notification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailProperties.class)
@Slf4j
@RequiredArgsConstructor
public class NotificationConfig {

    private final EmailProperties properties;

    /**
     * EmailProperties intentionally contains no code-level defaults.
     * application.yml is the single source of truth.
     *
     * Validate required configuration during startup so configuration
     * mistakes fail fast instead of surfacing as runtime email failures.
     */
    @PostConstruct
    void validate() {
        StringBuilder missing = new StringBuilder();

        if (properties.getResendBaseUrl() == null || properties.getResendBaseUrl().isBlank()) {
            missing.append("\n  - subpilot.email.resend-base-url");
        }

        if (properties.getBrevoBaseUrl() == null || properties.getBrevoBaseUrl().isBlank()) {
            missing.append("\n  - subpilot.email.brevo-base-url");
        }

        if (properties.getFromEmail() == null || properties.getFromEmail().isBlank()) {
            missing.append("\n  - subpilot.email.from-email");
        }

        if (properties.getFromName() == null || properties.getFromName().isBlank()) {
            missing.append("\n  - subpilot.email.from-name");
        }

        if (properties.getConnectTimeoutMs() <= 0) {
            missing.append("\n  - subpilot.email.connect-timeout-ms (must be > 0)");
        }

        if (properties.getReadTimeoutMs() <= 0) {
            missing.append("\n  - subpilot.email.read-timeout-ms (must be > 0)");
        }

        /*
         * NOTE: properties.getProvider() is no longer consulted by
         * FallbackEmailSender's routing at all -- it always tries Resend
         * first (skipping straight to Brevo if unconfigured), then falls
         * back to Brevo on any real Resend failure. That field is
         * effectively informational/vestigial now; validation below
         * checks the actual send-time behavior, not the provider string,
         * so this doesn't silently drift out of sync with it again.
         *
         * Both keys are required when enabled=true, not just one -- if
         * both were allowed to be blank, every email would silently
         * degrade to log-only forever in a real deployment, which is fine
         * for dev/CI (properties.isEnabled()=false covers that case
         * explicitly) but shouldn't happen unnoticed in production.
         */
        if (properties.isEnabled()) {
            if (properties.getResendApiKey() == null || properties.getResendApiKey().isBlank()) {
                missing.append("\n  - subpilot.email.resend-api-key (required when email.enabled=true)");
            }
            if (properties.getBrevoApiKey() == null || properties.getBrevoApiKey().isBlank()) {
                missing.append("\n  - subpilot.email.brevo-api-key (required when email.enabled=true -- kept as automatic fallback)");
            }
        }

        if (missing.length() > 0) {
            throw new IllegalStateException(
                    "subpilot.email.* configuration is incomplete. " +
                            "Set the following properties in application.yml or via environment variables:"
                            + missing);
        }

        log.info(
                "Email configuration loaded. enabled={}, from={}, resendBaseUrl={}, brevoBaseUrl={}",
                properties.isEnabled(),
                properties.getFromEmail(),
                properties.getResendBaseUrl(),
                properties.getBrevoBaseUrl()
        );
    }
}