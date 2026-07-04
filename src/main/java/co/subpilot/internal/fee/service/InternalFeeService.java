package co.subpilot.internal.fee.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.internal.admin.InternalAdminRole;
import co.subpilot.internal.admin.security.InternalAdminContext;
import co.subpilot.internal.audit.service.InternalAuditService;
import co.subpilot.internal.fee.entity.PlatformFeeDefault;
import co.subpilot.internal.fee.repository.PlatformFeeDefaultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class InternalFeeService {

    private final PlatformFeeDefaultRepository repository;
    private final InternalAuditService auditService;

    // Bootstrap fallback only — see getOrBootstrap().
    @Value("${subpilot.fees.default-bps:150}")
    private int fallbackBps;

    @Value("${subpilot.fees.default-fixed-minor:10000}")
    private long fallbackFixedMinor;

    /**
     * Creates the singleton row from the yml fallback on first read if it
     * doesn't exist yet — so an existing deployment upgrading to this
     * feature doesn't start with a broken/empty default. Once this row
     * exists, PlatformFeePolicy always reads from it, never from yml
     * again — see PlatformFeePolicy.calculate.
     */
    @Transactional
    public PlatformFeeDefault getOrBootstrap() {
        return repository.findById(PlatformFeeDefault.SINGLETON_ID)
                .orElseGet(() -> repository.save(PlatformFeeDefault.builder()
                        .feeBps(fallbackBps)
                        .fixedFeeMinor(fallbackFixedMinor)
                        .build()));
    }

    @Transactional
    public PlatformFeeDefault update(int feeBps, long fixedFeeMinor, String reason) {
        requireSuperAdmin();
        validate(feeBps, fixedFeeMinor);

        PlatformFeeDefault current = getOrBootstrap();
        Map<String, Object> oldValue = Map.of("feeBps", current.getFeeBps(), "fixedFeeMinor", current.getFixedFeeMinor());

        current.setFeeBps(feeBps);
        current.setFixedFeeMinor(fixedFeeMinor);
        current.setUpdatedByAdminId(InternalAdminContext.requireAdminId());
        PlatformFeeDefault saved = repository.save(current);

        Map<String, Object> newValue = Map.of("feeBps", feeBps, "fixedFeeMinor", fixedFeeMinor);
        auditService.record("platform_fee_policy", PlatformFeeDefault.SINGLETON_ID,
                "platform_fee_updated", oldValue, newValue, reason);

        return saved;
    }

    private void validate(int feeBps, long fixedFeeMinor) {
        if (feeBps < 0 || feeBps > 10_000) {
            throw new BusinessRuleException("invalid_fee_bps", "feeBps must be between 0 and 10000 (0%-100%).");
        }
        if (fixedFeeMinor < 0) {
            throw new BusinessRuleException("invalid_fixed_fee", "fixedFeeMinor must not be negative.");
        }
    }

    private void requireSuperAdmin() {
        if (!InternalAdminRole.SUPER_ADMIN.equals(InternalAdminContext.getRole())) {
            throw new AccessDeniedException("Only super_admin can modify platform fee policy.");
        }
    }
}