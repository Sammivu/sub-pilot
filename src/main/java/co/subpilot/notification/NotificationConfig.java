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

        if (properties.getProvider() == null || properties.getProvider().isBlank()) {
            missing.append("\n  - subpilot.email.provider");
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
         * Only validate provider-specific credentials when email
         * delivery is enabled.
         */
        if (properties.isEnabled()) {

            if ("brevo".equalsIgnoreCase(properties.getProvider())) {

                if (properties.getBrevoApiKey() == null
                        || properties.getBrevoApiKey().isBlank()) {

                    missing.append("\n  - subpilot.email.brevo-api-key (required when email.enabled=true)");
                }
            }
        }

        if (missing.length() > 0) {
            throw new IllegalStateException(
                    "subpilot.email.* configuration is incomplete. " +
                            "Set the following properties in application.yml or via environment variables:"
                            + missing);
        }

        log.info(
                "Email configuration loaded. provider={}, enabled={}, from={}, baseUrl={}",
                properties.getProvider(),
                properties.isEnabled(),
                properties.getFromEmail(),
                properties.getBrevoBaseUrl()
        );
    }
}