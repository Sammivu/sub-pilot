package co.subpilot.nomba;

import co.subpilot.common.exception.NombaApiException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtains and caches Nomba's OAuth2 access token using the Client-Credentials
 * grant (POST /v1/auth/token/issue).
 *
 * Tokens are cached in memory and refreshed proactively (60s before expiry)
 * so every Nomba API call doesn't re-authenticate. Thread-safe via a simple
 * lock — token refresh is infrequent enough that this is not a bottleneck.
 *
 * Only active when subpilot.nomba.mock-mode=false (real API mode).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "subpilot.nomba.mock-mode", havingValue = "false")
public class NombaAuthTokenManager {

    private final WebClient webClient;
    private final NombaApiProperties properties;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    // Refresh this many seconds before actual expiry to avoid using a token
    // that dies mid-request.
    private static final long REFRESH_SKEW_SECONDS = 300;

    public NombaAuthTokenManager(NombaApiProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    public String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }

        lock.lock();
        try {
            // Re-check after acquiring the lock — another thread may have refreshed already.
            if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
                return cachedToken;
            }
            return fetchNewToken();
        } finally {
            lock.unlock();
        }
    }

    private String fetchNewToken() {
        log.info("Fetching new Nomba OAuth2 access token (client-credentials grant)");

        Map<String, String> body = Map.of(
                "grant_type", "client_credentials",
                "client_id", properties.getClientId(),
                "client_secret", properties.getClientSecret()
        );

        JsonNode response = webClient.post()
                .uri("/v1/auth/token/issue")
                .header("accountId", properties.getAccountId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(properties.getConnectTimeoutMs() + properties.getReadTimeoutMs()))
                .block();

        if (response == null || response.path("data").path("access_token").isMissingNode()) {
            throw new NombaApiException("Failed to obtain Nomba access token — empty or malformed response");
        }

        JsonNode data = response.path("data");
        String token = data.path("access_token").asText();
        // Nomba doesn't always echo expires_in consistently across docs versions;
        // default to a conservative 20 minutes if absent.
        long expiresInSeconds = data.path("expires_in").asLong(3600);

        this.cachedToken = token;
        this.expiresAt = Instant.now().plusSeconds(Math.max(60, expiresInSeconds - REFRESH_SKEW_SECONDS));

        log.info("Nomba access token refreshed, valid for ~{}s", expiresInSeconds);
        return token;
    }

    /** Forces a refresh on the next call — used if a request comes back 401. */
    public void invalidate() {
        lock.lock();
        try {
            this.cachedToken = null;
            this.expiresAt = Instant.EPOCH;
        } finally {
            lock.unlock();
        }
    }
}