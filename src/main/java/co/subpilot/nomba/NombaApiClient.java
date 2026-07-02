package co.subpilot.nomba;

import co.subpilot.common.exception.NombaApiException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP plumbing for every real Nomba API call: attaches the OAuth2
 * bearer token + accountId header, applies timeouts, retries once on 401
 * (in case the cached token expired early), and translates failures into
 * NombaApiException so callers don't have to deal with WebClient internals.
 *
 * Only active when subpilot.nomba.mock-mode=false.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "subpilot.nomba.mock-mode", havingValue = "false")
public class NombaApiClient {

    private final WebClient webClient;
    private final NombaApiProperties properties;
    private final NombaAuthTokenManager tokenManager;

    public NombaApiClient(NombaApiProperties properties, NombaAuthTokenManager tokenManager) {
        this.properties = properties;
        this.tokenManager = tokenManager;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    public JsonNode post(String path, Map<String, Object> body) {
        log.info("POST URL PATH={}", path);
        return execute(path, "POST", body, false);
    }

    /** Variant that retries the request once after invalidating the token, if the first attempt 401s. */
    private JsonNode execute(String path, String method, Map<String, Object> body, boolean isRetry) {
        try {
            return webClient.post()
                    .uri(path)
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .header("accountId", properties.getAccountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(properties.getConnectTimeoutMs() + properties.getReadTimeoutMs()))
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(401) && !isRetry) {
                log.warn("Nomba API returned 401 — invalidating cached token and retrying once. path={}", path);
                tokenManager.invalidate();
                return execute(path, method, body, true);
            }
            log.error("Nomba API error [{}] on {}: {}", e.getStatusCode(), path, e.getResponseBodyAsString());
            throw new NombaApiException(
                    "Nomba API call to " + path + " failed with status " + e.getStatusCode() + ": " + e.getResponseBodyAsString(),
                    e
            );
        } catch (Exception e) {
            log.error("Nomba API call to {} failed: {}", path, e.getMessage(), e);
            throw new NombaApiException("Nomba API call to " + path + " failed: " + e.getMessage(), e);
        }
    }

    public JsonNode get(String path) {
        log.info("GET URL PATH={}", path);
        try {
            return webClient.get()
                    .uri(path)
                    .header("Authorization", "Bearer " + tokenManager.getAccessToken())
                    .header("accountId", properties.getAccountId())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(properties.getConnectTimeoutMs() + properties.getReadTimeoutMs()))
                    .block();
        } catch (Exception e) {
            log.error("Nomba GET {} failed: {}", path, e.getMessage(), e);
            throw new NombaApiException("Nomba GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    /** True if the response envelope indicates success: { "code": "00", ... }. */
    public boolean isSuccessEnvelope(JsonNode response) {
        return response != null && "00".equals(response.path("code").asText());
    }
}