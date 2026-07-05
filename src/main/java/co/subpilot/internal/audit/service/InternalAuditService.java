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
import java.time.temporal.ChronoUnit;

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

//    public Page<InternalAuditLog> search(String merchantId, String actorAdminId, String actionType,
//                                          Instant from, Instant to, Pageable pageable) {
//        return repository.search(merchantId, actorAdminId, actionType, from, to, pageable);
//    }
    public Page<InternalAuditLog> search(String merchantId, String actorAdminId, String actionType,
                                         Instant from, Instant to, Pageable pageable) {

        // Postgres has no bytea -> timestamp cast (unlike bytea -> text, which
        // is why CAST(:q AS string) works for the string-search queries
        // elsewhere in this codebase). A CAST in the JPQL can't fix a null
        // Instant parameter the way it fixes a null String one — the only
        // reliable fix here is never letting null reach the query at all.
        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo = to != null ? to : Instant.parse("9999-12-31T23:59:59Z");
        return repository.search(merchantId, actorAdminId, actionType, effectiveFrom, effectiveTo, pageable);
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