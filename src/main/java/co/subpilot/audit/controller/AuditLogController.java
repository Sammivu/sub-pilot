package co.subpilot.audit.controller;

import co.subpilot.audit.entity.AuditLog;
import co.subpilot.audit.repository.AuditLogRepository;
import co.subpilot.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Read-only access to the audit trail (PRD §6.11). Write access only ever
 * happens through AuditLogService, called from the controllers that perform
 * the actual mutations — there's no POST/PUT here by design, since an audit
 * log entry is a byproduct of an action, never a directly-created resource.
 */
@RestController
@RequestMapping("/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<Page<AuditLog>> list(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int perPage
    ) {
        String merchantId = TenantContext.requireMerchantId();
        var pageable = PageRequest.of(page, Math.min(perPage, 100), Sort.by("createdAt").descending());

        Page<AuditLog> result;
        if (resourceType != null && resourceId != null) {
            result = auditLogRepository.findByMerchantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
                    merchantId, resourceType, resourceId, pageable);
        } else if (action != null) {
            result = auditLogRepository.findByMerchantIdAndActionOrderByCreatedAtDesc(merchantId, action, pageable);
        } else {
            result = auditLogRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
        }

        return ResponseEntity.ok(result);
    }
}