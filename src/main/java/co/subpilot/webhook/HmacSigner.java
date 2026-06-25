package co.subpilot.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing for outbound webhook payloads.
 * Used to populate the X-SubPilot-Signature header (PRD §10).
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSigner() {}

    public static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign webhook payload", e);
        }
    }

    public static boolean verify(String payload, String secret, String providedSignature) {
        String expected = sign(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }
}