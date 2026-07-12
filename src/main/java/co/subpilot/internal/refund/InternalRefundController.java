package co.subpilot.internal.refund;

import co.subpilot.internal.admin.InternalAdminRole;
import co.subpilot.internal.admin.security.InternalAdminContext;
import co.subpilot.refund.dto.RefundDtos;
import co.subpilot.refund.entity.Refund;
import co.subpilot.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the earlier AdminRefundController (guarded by a throwaway
 * static X-Admin-Key header) now that a real InternalAdmin auth system
 * exists. Moved under /v1/internal so InternalAdminAuthFilter covers it
 * automatically — no separate filter needed. Refund approval moves real
 * money out of the pooled central wallet, same sensitivity tier as
 * platform fee policy, so it's super_admin-only, not ops_admin.
 */
@RestController
@RequestMapping("/v1/internal/refunds")
@RequiredArgsConstructor
public class InternalRefundController {

    private final RefundService refundService;

    @GetMapping
    public ResponseEntity<List<RefundDtos.RefundResponse>> listPendingApproval() {
        requireSuperAdmin();
        List<RefundDtos.RefundResponse> refunds = refundService.listPendingApproval().stream()
                .map(RefundDtos.RefundResponse::from)
                .toList();
        return ResponseEntity.ok(refunds);
    }
    @GetMapping("/all")
    public ResponseEntity<Page<RefundDtos.AdminRefundResponse>> list(
            @RequestParam(defaultValue = "pending_approval") String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String resolvedBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "20")  int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        Map<String, String> sortFieldMap = Map.of(
                "createdAt",  "created_at",
                "resolvedAt", "resolved_at",
                "amount",     "amount",
                "status",     "status",
                "merchantId", "merchant_id"
        );

        String columnName = sortFieldMap.getOrDefault(sortBy, "created_at");

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(columnName).ascending()
                : Sort.by(columnName).descending();

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), sort);

        Instant fromInstant = from != null ? Instant.parse(from) : null;
        Instant toInstant   = to   != null ? Instant.parse(to)   : null;

        return ResponseEntity.ok(
                refundService.listWithFilters(status, merchantId, resolvedBy, fromInstant, toInstant, pageable)
                        .map(RefundDtos.AdminRefundResponse::from)
        );
    }

    @PostMapping("/{refundId}/approve")
    public ResponseEntity<RefundDtos.RefundResponse> approve(@PathVariable String refundId,
                                 @RequestHeader(value = "X-Admin-Identity", defaultValue = "unknown") String adminIdentity
    ) {
        requireSuperAdmin();
        Refund refund = refundService.approve(refundId, adminIdentity);
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refund));
    }

    @PostMapping("/{refundId}/reject")
    public ResponseEntity<RefundDtos.RefundResponse> reject(
            @PathVariable String refundId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-Admin-Identity", defaultValue = "unknown") String adminIdentity

    ) {
        requireSuperAdmin();
        String reason = body != null ? body.get("reason") : null;
        Refund refund = refundService.reject(refundId, reason, adminIdentity);
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refund));
    }

    // Single refund detail — useful for audit drill-down
    @GetMapping("/{refundId}")
    public ResponseEntity<RefundDtos.RefundResponse> get(@PathVariable String refundId) {
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refundService.findById(refundId)));
    }

    private void requireSuperAdmin() {
        if (!InternalAdminRole.SUPER_ADMIN.equals(InternalAdminContext.getRole())) {
            throw new AccessDeniedException("Only super_admin can approve or reject refunds.");
        }
    }
}