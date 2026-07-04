package co.subpilot.internal.audit.service;

import co.subpilot.internal.admin.security.InternalAdminContext;
import co.subpilot.internal.audit.entity.InternalAuditLog;
import co.subpilot.internal.audit.repository.InternalAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class InternalAuditService {

    private final InternalAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Reads actor identity from InternalAdminContext (the currently
     * authenticated admin), never from a parameter — same rationale as
     * TenantContext: identity comes from the authenticated principal, not
     * from anything the caller passes in, so a service can never
     * accidentally attribute an action to the wrong admin.
     */
    public InternalAuditLog record(String targetType, String targetId, String actionType,
                                    Object oldValue, Object newValue, String reason) {
        InternalAuditLog log = InternalAuditLog.builder()
                .actorAdminId(InternalAdminContext.requireAdminId())
                .actorEmail(InternalAdminContext.getEmail())
                .targetType(targetType)
                .targetId(targetId)
                .actionType(actionType)
                .oldValue(toJson(oldValue))
                .newValue(toJson(newValue))
                .reason(reason)
                .build();
        return repository.save(log);
    }

    public Page<InternalAuditLog> search(String merchantId, String actorAdminId, String actionType,
                                          Instant from, Instant to, Pageable pageable) {
        return repository.search(merchantId, actorAdminId, actionType, from, to, pageable);
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"could not serialize\"}";
        }
    }
}