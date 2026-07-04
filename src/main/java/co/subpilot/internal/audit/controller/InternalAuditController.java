package co.subpilot.internal.audit.controller;

import co.subpilot.internal.audit.dto.InternalAuditDtos;
import co.subpilot.internal.audit.service.InternalAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/v1/internal/audit")
@RequiredArgsConstructor
public class InternalAuditController {

    private final InternalAuditService auditService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<Page<InternalAuditDtos.AuditLogResponse>> search(
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String actorAdminId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        var pageable = PageRequest.of(page, size);
        var results = auditService.search(merchantId, actorAdminId, actionType, from, to, pageable);
        return ResponseEntity.ok(results.map(a -> InternalAuditDtos.AuditLogResponse.from(a, objectMapper)));
    }
}