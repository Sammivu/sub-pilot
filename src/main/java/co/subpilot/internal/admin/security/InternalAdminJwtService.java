package co.subpilot.internal.admin.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Deliberately a SEPARATE signing key from the merchant JwtService
 * (subpilot.internal.jwt.secret, not subpilot.jwt.secret) — this is what
 * makes cross-authentication structurally impossible, not just
 * conventionally avoided: a merchant JWT cannot be verified with this
 * key, and an internal admin JWT cannot be verified by the merchant
 * JwtService, regardless of what cookie name or path either one arrives
 * under.
 */
@Slf4j
@Component
public class InternalAdminJwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public InternalAdminJwtService(@Value("${subpilot.internal.jwt.secret}") String secret,
                                    @Value("${subpilot.internal.jwt.expiration-ms}") long expirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(ensureMinKeyLength(secret));
        this.expirationMs = expirationMs;
    }

    private static byte[] ensureMinKeyLength(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 32) return bytes;
        byte[] padded = new byte[32];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        return padded;
    }

    public String generateToken(String adminId, String role, String email) {
        return Jwts.builder()
                .subject(adminId)
                .claim("role", role)
                .claim("email", email)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(signingKey)
                .compact();
    }

    public Claims validateAndExtract(String token) {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            validateAndExtract(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid internal admin JWT: {}", e.getMessage());
            return false;
        }
    }
}