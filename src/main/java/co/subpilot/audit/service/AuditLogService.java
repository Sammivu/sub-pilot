package co.subpilot.audit.service;

import co.subpilot.audit.AuditActorType;
import co.subpilot.audit.entity.AuditLog;
import co.subpilot.audit.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Single entry point for writing to the audit trail (PRD §6.11).
 *
 * Resolves the acting identity directly from Spring Security's context
 * rather than requiring every caller to pass it explicitly — AuthFilter
 * already populates SecurityContextHolder with either:
 *   - principal = userId,        authority = ROLE_MERCHANT  (dashboard)
 *   - principal = apiKey.getId(), authority = ROLE_API_KEY   (downstream API)
 * which is exactly actor_id / actor_type.
 *
 * Like EventService, runs in its own transaction (REQUIRES_NEW) so an
 * audit-write failure never rolls back the business operation that
 * triggered it — losing an audit row is bad, but blocking a plan archive
 * because the audit insert hiccuped would be worse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param before the resource's state before the change, or null for a creation
     * @param after  the resource's state after the change, or null for a deletion
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String merchantId, String action, String resourceType, String resourceId,
                        Object before, Object after) {
        try {
            String actorId = resolveActorId();
            String actorType = resolveActorType();

            AuditLog log = AuditLog.builder()
                    .id(UlidCreator.getMonotonicUlid().toString())
                    .merchantId(merchantId)
                    .actorId(actorId)
                    .actorType(actorType)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .beforeSnapshot(before != null ? objectMapper.writeValueAsString(before) : null)
                    .afterSnapshot(after != null ? objectMapper.writeValueAsString(after) : null)
                    .createdAt(Instant.now())
                    .build();

            auditLogRepository.save(log);
        } catch (Exception e) {
            // Mirrors EventService's defensive stance — never let an audit
            // write failure surface as an error to the caller.
            log.error("Failed to record audit log action={} merchant={} resource={}/{}: {}",
                    action, merchantId, resourceType, resourceId, e.getMessage(), e);
        }
    }

    /** Convenience overload for actions with no before-state (creation). */
    public void recordCreation(String merchantId, String action, String resourceType, String resourceId, Object after) {
        record(merchantId, action, resourceType, resourceId, null, after);
    }

    /** Convenience overload for actions with no after-state (deletion/revocation). */
    public void recordDeletion(String merchantId, String action, String resourceType, String resourceId, Object before) {
        record(merchantId, action, resourceType, resourceId, before, null);
    }

    private String resolveActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return "system"; // background jobs (billing engine, dunning scheduler) have no HTTP-request actor
        }
        return auth.getName();
    }

    private String resolveActorType() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return "system";
        }
        boolean isApiKey = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_API_KEY"::equals);
        return isApiKey ? AuditActorType.API_KEY : AuditActorType.USER;
    }
}