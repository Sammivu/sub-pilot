package co.subpilot.auth.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long jwtExpirationMs;

    public JwtService(@Value("${subpilot.jwt.secret}") String jwtSecret,
                      @Value("${subpilot.jwt.expiration-ms}") long jwtExpirationMs) {

        this.signingKey = Keys.hmacShaKeyFor(ensureMinKeyLength(jwtSecret));
        this.jwtExpirationMs = jwtExpirationMs;
    }

    /** HMAC-SHA256 requires a key of at least 32 bytes; pad defensively rather than crash on a short secret. */
    private static byte[] ensureMinKeyLength(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 32) return bytes;
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    public String generateToken(String userId, String merchantId, String email) {
        return Jwts.builder()
                .subject(userId)
                .claim("merchantId", merchantId)
                .claim("email", email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(signingKey)
                .compact();
    }

    public Claims validateAndExtract(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            validateAndExtract(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public String extractUserId(String token) {
        return validateAndExtract(token).getSubject();
    }

    public String extractMerchantId(String token) {
        return validateAndExtract(token).get("merchantId", String.class);
    }
}