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
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional
    public AuthDtos.AuthResponse signup(AuthDtos.SignupRequest req) {
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
        log.info("New merchant signed up: {} ({})", merchant.getBusinessName(), merchant.getId());

        return new AuthDtos.AuthResponse(token, merchant.getId(), user.getId(),
                user.getEmail(), merchant.getBusinessName());
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        Merchant merchant = merchantRepository.findById(user.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant", user.getMerchantId()));

        String token = jwtService.generateToken(user.getId(), merchant.getId(), user.getEmail());
        return new AuthDtos.AuthResponse(token, merchant.getId(), user.getId(),
                user.getEmail(), merchant.getBusinessName());
    }

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