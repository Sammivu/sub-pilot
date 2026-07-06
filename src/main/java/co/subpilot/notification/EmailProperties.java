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

    private String provider;
    private String resendApiKey;
    private String resendBaseUrl;
    private String brevoApiKey;
    private String brevoBaseUrl;
    private String fromEmail;
    private String fromName;
    private boolean enabled; // set false to no-op all sends (e.g. in CI)
    private long connectTimeoutMs;
    private long readTimeoutMs;

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

    public String getResendApiKey() {
        return resendApiKey;
    }

    public void setResendApiKey(String resendApiKey) {
        this.resendApiKey = resendApiKey;
    }

    public String getResendBaseUrl() {
        return resendBaseUrl;
    }

    public void setResendBaseUrl(String resendBaseUrl) {
        this.resendBaseUrl = resendBaseUrl;
    }
}