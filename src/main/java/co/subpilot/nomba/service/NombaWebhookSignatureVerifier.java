package co.subpilot.nomba.service;

import co.subpilot.nomba.NombaApiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Verifies the HMAC-SHA256 signature Nomba attaches to inbound webhooks.
 *
 * Per Nomba's documented scheme (developer.nomba.com/products/webhooks/signature-verification-new):
 *
 *   hashingPayload = "{event_type}:{requestId}:{merchant.userId}:{merchant.walletId}
 *                      :{transaction.transactionId}:{transaction.type}
 *                      :{transaction.time}:{transaction.responseCode}"
 *   message        = "{hashingPayload}:{timestamp}"
 *   signature      = base64(HMAC_SHA256(message, signingSecret))
 *
 * The timestamp is supplied by Nomba in a request header alongside the
 * signature (commonly seen as a numeric epoch). Both must be present and
 * matched against the payload to accept the webhook.
 *
 * Only active when subpilot.nomba.mock-mode=false — MockNombaGateway
 * always returns true for verification in dev/demo mode.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "subpilot.nomba.mock-mode", havingValue = "false")
public class NombaWebhookSignatureVerifier {

    private final NombaApiProperties properties;

    public NombaWebhookSignatureVerifier(NombaApiProperties properties) {
        this.properties = properties;
    }

    /**
     * @param payload   the parsed JSON body of the inbound webhook
     * @param timestamp the value of the timestamp header Nomba sent alongside the signature
     * @param providedSignature the value of the signature header Nomba sent
     */
    public boolean verify(JsonNode payload, String timestamp, String providedSignature) {
        // ... null checks ...

        try {
            String hashingPayload = buildHashingPayload(payload, timestamp); // :white_check_mark: pass timestamp in

            // :white_check_mark: hash the full string directly — no extra concatenation
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            ));
            byte[] hash = mac.doFinal(hashingPayload.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(hash);

            log.info("Hashing payload: [{}]", hashingPayload);  // keep this for debugging
            log.info("Computed: {} | Provided: {}", computedSignature, providedSignature);

            return MessageDigest.isEqual(
                    computedSignature.getBytes(StandardCharsets.UTF_8),
                    providedSignature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Failed to compute Nomba webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Reconstructs the colon-joined field string Nomba signs over, per their
     * documented Go example. Missing fields are treated as empty strings
     * rather than failing closed on shape — webhook payload shape can vary
     * slightly between event types, and a missing field should fail the
     * signature comparison naturally rather than throw.
     */
    private String buildHashingPayload(JsonNode payload, String timestamp) {
        JsonNode data = payload.path("data");
        JsonNode merchant = data.path("merchant");
        JsonNode transaction = data.path("transaction");

        String transactionResponseCode = textOrEmpty(transaction, "responseCode");
        if ("null".equalsIgnoreCase(transactionResponseCode)) {
            transactionResponseCode = "";
        }

        return String.join(":",
                textOrEmpty(payload, "event_type"),
                textOrEmpty(payload, "requestId"),
                textOrEmpty(merchant, "userId"),
                textOrEmpty(merchant, "walletId"),
                textOrEmpty(transaction, "transactionId"),
                textOrEmpty(transaction, "type"),
                textOrEmpty(transaction, "time"),
                transactionResponseCode,
                timestamp                          // :white_check_mark: 9th segment
        );
    }

    private String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }
}