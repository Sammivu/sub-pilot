package co.subpilot.nomba;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;


/**
 * Registers NombaApiProperties for binding from subpilot.nomba.* config.
 *
 * The real Nomba integration (NombaApiClient, NombaAuthTokenManager) builds
 * its own internal WebClient rather than consuming a Spring-managed
 * WebClient/RestClient bean — there's intentionally no shared HTTP client
 * bean here. Keeping the client construction inside NombaApiClient /
 * NombaAuthTokenManager (rather than exposing it as a general-purpose bean)
 * avoids another module accidentally reusing a Nomba-specific client
 * (baseUrl, default headers) for something unrelated.
 */
@Configuration
@EnableConfigurationProperties(NombaApiProperties.class)
@RequiredArgsConstructor
public class NombaConfig {

    private final NombaApiProperties properties;

    /**
     * NombaApiProperties has no code-level defaults by design (see its
     * javadoc) — YAML is the single source of truth. The tradeoff is that a
     * missing key silently becomes false/0/null instead of a sensible
     * fallback, which is worse than no config at all (a 0ms timeout fails
     * every real call instantly; a silently-false mockMode sends you to the
     * real API when you meant to stay in mock mode). This fails startup
     * loudly instead, with a message that says exactly which YAML key is
     * missing, rather than surfacing as a confusing runtime symptom later.
     */
    @PostConstruct
    void validate() {
        StringBuilder missing = new StringBuilder();

        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            missing.append("\n  - subpilot.nomba.base-url");
        }
        if (properties.getConnectTimeoutMs() <= 0) {
            missing.append("\n  - subpilot.nomba.connect-timeout-ms (must be > 0)");
        }
        if (properties.getReadTimeoutMs() <= 0) {
            missing.append("\n  - subpilot.nomba.read-timeout-ms (must be > 0)");
        }

        // Only required in real-API mode — mock mode never calls Nomba, so
        // there's nothing to authenticate. mockMode itself is never
        // "missing" (false is a valid, meaningful value), so it's not checked here.
        if (!properties.isMockMode()) {
            if (properties.getAccountId() == null || properties.getAccountId().isBlank()) {
                missing.append("\n  - subpilot.nomba.account-id (required when mock-mode=false)");
            }
            if (properties.getClientId() == null || properties.getClientId().isBlank()) {
                missing.append("\n  - subpilot.nomba.client-id (required when mock-mode=false)");
            }
            if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
                missing.append("\n  - subpilot.nomba.client-secret (required when mock-mode=false)");
            }
            if (properties.getWebhookSecret() == null || properties.getWebhookSecret().isBlank()) {
                missing.append("\n  - subpilot.nomba.webhook-secret (required when mock-mode=false)");
            }
        }

        if (missing.length() > 0) {
            throw new IllegalStateException(
                    "subpilot.nomba.* configuration is incomplete — refusing to start with silent " +
                            "false/0/null fallbacks. Set the following in application.yml or as env vars:" + missing);
        }
    }
}