package co.subpilot.auth.service;

import co.subpilot.auth.dto.AuthDtos;
import co.subpilot.auth.entity.ApiKey;
import co.subpilot.auth.entity.User;
import co.subpilot.auth.repository.ApiKeyRepository;
import co.subpilot.auth.repository.UserRepository;
import co.subpilot.auth.security.JwtService;
import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.dunning.service.DunningTriggerService;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final DunningTriggerService dunningTriggerService;

    @Value("${subpilot.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    @Transactional
    public AuthResult signup(AuthDtos.SignupRequest req) {
        if (merchantRepository.existsByEmail(req.email())) {
            throw new BusinessRuleException("email_taken", "An account with this email already exists.");
        }

        String slug = generateMerchantSlug(req.businessName());

        Merchant merchant = merchantRepository.save(Merchant.builder()
                .businessName(req.businessName())
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .slug(slug)
                .webhookSigningSecret(generateSecret())
                .build());

        User user = userRepository.save(User.builder()
                .merchantId(merchant.getId())
                .email(req.email())
                .name(req.businessName())
                .role("owner")
                .passwordHash(passwordEncoder.encode(req.password()))
                .build());

        String token = jwtService.generateToken(user.getId(), merchant.getId(), user.getEmail());
        String refreshToken = issueRefreshToken(user);

        // Gap 3: give every merchant a default dunning campaign immediately,
        // not just after their first payment failure — otherwise the
        // dunning settings screen has nothing to show a brand-new merchant.
        dunningTriggerService.createDefaultCampaign(merchant.getId());

        log.info("New merchant signed up: {} ({})", merchant.getBusinessName(), merchant.getId());

        return new AuthResult(
                new AuthDtos.AuthResponse(merchant.getId(), user.getId(), user.getEmail(), merchant.getBusinessName()),
                token, refreshToken
        );
    }

    @Transactional
    public AuthResult login(AuthDtos.LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        user.setLastLoginAt(Instant.now());

        Merchant merchant = merchantRepository.findById(user.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", user.getMerchantId()));

        String token = jwtService.generateToken(user.getId(), merchant.getId(), user.getEmail());
        String refreshToken = issueRefreshToken(user); // also persists user (lastLoginAt + new refresh hash) in one save

        return new AuthResult(
                new AuthDtos.AuthResponse(merchant.getId(), user.getId(), user.getEmail(), merchant.getBusinessName()),
                token, refreshToken
        );
    }

    /**
     * Gap 4 — exchanges a valid, unexpired refresh token for a brand-new
     * access token + refresh token pair (rotation: the old refresh token is
     * invalidated the moment a new one is issued, so a stolen refresh token
     * can only be used once before the legitimate holder's next refresh
     * silently invalidates it).
     */
    @Transactional
    public AuthResult refresh(AuthDtos.RefreshRequest req) {
        String hash = sha256(req.refreshToken());
        User user = userRepository.findByRefreshTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired refresh token"));

        if (user.getRefreshTokenExpiresAt() == null || user.getRefreshTokenExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Invalid or expired refresh token");
        }

        Merchant merchant = merchantRepository.findById(user.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", user.getMerchantId()));

        String token = jwtService.generateToken(user.getId(), merchant.getId(), user.getEmail());
        String newRefreshToken = issueRefreshToken(user); // rotates — old hash is overwritten

        return new AuthResult(
                new AuthDtos.AuthResponse(merchant.getId(), user.getId(), user.getEmail(), merchant.getBusinessName()),
                token, newRefreshToken
        );
    }

    /** Gap 6 — expires the session by clearing the stored refresh token, so it can no longer be exchanged either. */
    @Transactional
    public void logout(String userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setRefreshTokenHash(null);
            user.setRefreshTokenExpiresAt(null);
            userRepository.save(user);
        });
    }

    /** Gap 5 — validates the current password before replacing it with a new (re-hashed) one. */
    @Transactional
    public void changePassword(String userId, AuthDtos.ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        // Invalidate the refresh token on password change — a classic
        // security practice: changing your password should kick out any
        // other long-lived session that might have been compromised.
        user.setRefreshTokenHash(null);
        user.setRefreshTokenExpiresAt(null);
        userRepository.save(user);

        log.info("Password changed for user={}", userId);
    }

    /**
     * Generates a fresh opaque refresh token (NOT a JWT — a JWT can't be
     * revoked without a denylist, which defeats the point; a random token
     * whose hash we look up is trivially revocable by clearing the stored
     * hash, exactly as logout() and changePassword() do above).
     */
    private String issueRefreshToken(User user) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        user.setRefreshTokenHash(sha256(rawToken));
        user.setRefreshTokenExpiresAt(Instant.now().plusMillis(refreshExpirationMs));
        userRepository.save(user);

        return rawToken;
    }

    /**
     * Carries both the public-safe AuthResponse body AND the access/refresh
     * tokens that AuthController needs to set as cookies — kept out of
     * AuthDtos.AuthResponse itself so there is no code path that could
     * accidentally serialize a token into a JSON response (the entire point
     * of Gap 6).
     */
    public record AuthResult(AuthDtos.AuthResponse body, String accessToken, String refreshToken) {}

    @Transactional
    public AuthDtos.ApiKeyResponse createApiKey(AuthDtos.CreateApiKeyRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        String rawKey = "sk_live_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = rawKey.substring(0, 16);
        String hash = sha256(rawKey);

        ApiKey apiKey = apiKeyRepository.save(ApiKey.builder()
                .merchantId(merchantId)
                .keyHash(hash)
                .prefix(prefix)
                .label(req.label())
                .build());

        return toApiKeyResponse(apiKey, rawKey);
    }

    public List<AuthDtos.ApiKeyResponse> listApiKeys() {
        String merchantId = TenantContext.requireMerchantId();
        return apiKeyRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)
                .stream()
                .map(k -> toApiKeyResponse(k, null))
                .toList();
    }

    @Transactional
    public void revokeApiKey(String keyId) {
        String merchantId = TenantContext.requireMerchantId();
        ApiKey key = apiKeyRepository.findById(keyId)
                .filter(k -> k.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new ResourceNotFoundException("ApiKey", keyId));

        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateMerchantSlug(String businessName) {
        String base = businessName.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (base.length() > 50) base = base.substring(0, 50);

        String slug = base;
        int attempts = 0;
        while (merchantRepository.existsBySlug(slug)) {
            slug = base + "-" + (++attempts);
        }
        return slug;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AuthDtos.ApiKeyResponse toApiKeyResponse(ApiKey key, String rawKey) {
        return new AuthDtos.ApiKeyResponse(
                key.getId(), key.getLabel(), key.getPrefix(), rawKey,
                key.getCreatedAt().toString(),
                key.getLastUsedAt() != null ? key.getLastUsedAt().toString() : null,
                key.isActive()
        );
    }
}