package co.subpilot.internal.merchant.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.internal.admin.InternalAdminRole;
import co.subpilot.internal.admin.security.InternalAdminContext;
import co.subpilot.internal.admin.service.InternalAdminNotificationService;
import co.subpilot.internal.audit.service.InternalAuditService;
import co.subpilot.internal.fee.entity.PlatformFeeDefault;
import co.subpilot.internal.fee.service.InternalFeeService;
import co.subpilot.merchant.MerchantStatus;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InternalMerchantService {

    /**
     * Server-side transition table — mirrors SubscriptionStateMachine's
     * pattern of enforcing valid transitions in one explicit place rather
     * than scattered if-checks. Whether ops_admin may call this at all is
     * checked separately in updateStatus() — this map only defines which
     * transitions are valid, not who's allowed to make them.
     */
    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            MerchantStatus.ACTIVE, Set.of(MerchantStatus.UNDER_REVIEW),
            MerchantStatus.UNDER_REVIEW, Set.of(MerchantStatus.ACTIVE, MerchantStatus.SUSPENDED),
            MerchantStatus.SUSPENDED, Set.of(MerchantStatus.ACTIVE)
    );

    /**
     * Open call from the spec — set true if ops_admin should be allowed to
     * change merchant status, false to make it super_admin-only like fees.
     * Defaulting to false (business decision the spec left open, now
     * resolved) — flip via config, not code, if that answer ever changes.
     */
    @Value("${subpilot.internal.ops-admin-can-change-status}")
    private boolean opsAdminCanChangeStatus;

    private final MerchantRepository merchantRepository;
    private final InternalFeeService internalFeeService;
    private final InternalAuditService auditService;
    private final InternalAdminNotificationService notificationService;

    public Page<Merchant> search(String query, String status, Pageable pageable) {
        return merchantRepository.search(query, status, pageable);
    }

    public Merchant getById(String merchantId) {
        return merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));
    }

    public record EffectiveFee(String feeSource, int feeBps, long fixedFeeMinor) {}

    public EffectiveFee resolveEffectiveFee(Merchant merchant) {
        if (merchant.getFeeBps() != null && merchant.getFeeFixedMinor() != null) {
            return new EffectiveFee("merchant_override", merchant.getFeeBps(), merchant.getFeeFixedMinor());
        }
        PlatformFeeDefault platformDefault = internalFeeService.getOrBootstrap();
        return new EffectiveFee("platform_default", platformDefault.getFeeBps(), platformDefault.getFixedFeeMinor());
    }

    public EffectiveFee getPlatformDefault() {
        PlatformFeeDefault platformDefault = internalFeeService.getOrBootstrap();
        return new EffectiveFee("platform_default", platformDefault.getFeeBps(), platformDefault.getFixedFeeMinor());
    }

    @Transactional
    public Merchant updateStatus(String merchantId, String newStatus, String reason) {
        if (!opsAdminCanChangeStatus && !InternalAdminRole.SUPER_ADMIN.equals(InternalAdminContext.getRole())) {
            throw new AccessDeniedException("Only super_admin can change merchant status.");
        }

        Merchant merchant = getById(merchantId);
        String oldStatus = merchant.getStatus();

        Set<String> allowed = ALLOWED_STATUS_TRANSITIONS.getOrDefault(oldStatus, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new BusinessRuleException("invalid_status_transition",
                    "Cannot transition merchant status from '" + oldStatus + "' to '" + newStatus + "'.");
        }

        merchant.setStatus(newStatus);
        Merchant saved = merchantRepository.save(merchant);

        auditService.record("merchant", merchantId, "merchant_status_changed",
                Map.of("status", oldStatus), Map.of("status", newStatus), reason);

        if (MerchantStatus.UNDER_REVIEW.equals(newStatus)) {
            notificationService.notifyMerchantUnderReview(merchantId, merchant.getBusinessName(), reason);
        }

        return saved;
    }

    @Transactional
    public Merchant updateFeeOverride(String merchantId, int overrideFeeBps, long overrideFixedFeeMinor, String reason) {
        requireSuperAdmin();
        if (overrideFeeBps < 0 || overrideFeeBps > 10_000) {
            throw new BusinessRuleException("invalid_fee_bps", "overrideFeeBps must be between 0 and 10000.");
        }
        if (overrideFixedFeeMinor < 0) {
            throw new BusinessRuleException("invalid_fixed_fee", "overrideFixedFeeMinor must not be negative.");
        }

        Merchant merchant = getById(merchantId);
        Map<String, Object> oldValue = Map.of(
                "overrideFeeBps", merchant.getFeeBps() != null ? merchant.getFeeBps() : "null",
                "overrideFixedFeeMinor", merchant.getFeeFixedMinor() != null ? merchant.getFeeFixedMinor() : "null");

        merchant.setFeeBps(overrideFeeBps);
        merchant.setFeeFixedMinor(overrideFixedFeeMinor);
        Merchant saved = merchantRepository.save(merchant);

        Map<String, Object> newValue = Map.of("overrideFeeBps", overrideFeeBps, "overrideFixedFeeMinor", overrideFixedFeeMinor);
        auditService.record("merchant", merchantId, "merchant_fee_override_updated", oldValue, newValue, reason);

        return saved;
    }

    @Transactional
    public Merchant removeFeeOverride(String merchantId, String reason) {
        requireSuperAdmin();

        Merchant merchant = getById(merchantId);
        Map<String, Object> oldValue = Map.of(
                "overrideFeeBps", merchant.getFeeBps() != null ? merchant.getFeeBps() : "null",
                "overrideFixedFeeMinor", merchant.getFeeFixedMinor() != null ? merchant.getFeeFixedMinor() : "null");

        merchant.setFeeBps(null);
        merchant.setFeeFixedMinor(null);
        Merchant saved = merchantRepository.save(merchant);

        auditService.record("merchant", merchantId, "merchant_fee_override_removed", oldValue,
                Map.of("overrideFeeBps", "null", "overrideFixedFeeMinor", "null"), reason);

        return saved;
    }

    private void requireSuperAdmin() {
        if (!InternalAdminRole.SUPER_ADMIN.equals(InternalAdminContext.getRole())) {
            throw new AccessDeniedException("Only super_admin can modify merchant fee overrides.");
        }
    }
}