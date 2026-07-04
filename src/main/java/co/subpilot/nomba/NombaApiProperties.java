package co.subpilot.nomba;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding of subpilot.nomba.* properties. Used by NombaApiClient and
 * NombaGatewayImpl so credentials are read once, in one place.
 *
 * Deliberately NO field defaults here — every value must come from YAML
 * (or an equivalent env var override). A hardcoded fallback on a class
 * like this is exactly the kind of thing that causes "wait, which value
 * is actually active?" confusion — if a key is missing from YAML, this
 * should fail loudly (see NombaApiPropertiesValidator) rather than
 * silently fall back to a value nobody can see without reading the source.
 */
@ConfigurationProperties(prefix = "subpilot.nomba")
public class NombaApiProperties {

    private String baseUrl;
    private String accountId;
    private String clientId;
    private String clientSecret;
    private boolean mockMode;
    private boolean sandbox;
    private String webhookSecret;
    private long connectTimeoutMs;
    private long readTimeoutMs;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public boolean isMockMode() { return mockMode; }
    public void setMockMode(boolean mockMode) { this.mockMode = mockMode; }

    public boolean isSandbox() { return sandbox; }
    public void setSandbox(boolean sandbox) { this.sandbox = sandbox; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }

    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}