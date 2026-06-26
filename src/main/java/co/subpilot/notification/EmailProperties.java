package co.subpilot.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding of subpilot.email.* properties.
 *
 * Brevo (formerly Sendinblue) is used as the transactional email provider.
 * Unlike SMTP-based providers, Brevo's REST API only needs a single API key
 * — no SMTP host/port/credential juggling.
 */
@ConfigurationProperties(prefix = "subpilot.email")
public class EmailProperties {

    private String provider = "brevo";
    private String brevoApiKey;
    private String brevoBaseUrl = "https://api.brevo.com/v3";
    private String fromEmail = "noreply@subpilot.co";
    private String fromName = "SubPilot";
    private boolean enabled = true; // set false to no-op all sends (e.g. in CI)
    private long connectTimeoutMs = 5000;
    private long readTimeoutMs = 10000;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getBrevoApiKey() { return brevoApiKey; }
    public void setBrevoApiKey(String brevoApiKey) { this.brevoApiKey = brevoApiKey; }

    public String getBrevoBaseUrl() { return brevoBaseUrl; }
    public void setBrevoBaseUrl(String brevoBaseUrl) { this.brevoBaseUrl = brevoBaseUrl; }

    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }

    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}