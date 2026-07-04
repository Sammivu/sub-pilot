package co.subpilot.internal.refund;

import co.subpilot.internal.admin.InternalAdminRole;
import co.subpilot.internal.admin.security.InternalAdminContext;
import co.subpilot.refund.dto.RefundDtos;
import co.subpilot.refund.entity.Refund;
import co.subpilot.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @PostMapping("/{refundId}/approve")
    public ResponseEntity<RefundDtos.RefundResponse> approve(@PathVariable String refundId) {
        requireSuperAdmin();
        Refund refund = refundService.approve(refundId);
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refund));
    }

    @PostMapping("/{refundId}/reject")
    public ResponseEntity<RefundDtos.RefundResponse> reject(
            @PathVariable String refundId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        requireSuperAdmin();
        String reason = body != null ? body.get("reason") : null;
        Refund refund = refundService.reject(refundId, reason);
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refund));
    }

    private void requireSuperAdmin() {
        if (!InternalAdminRole.SUPER_ADMIN.equals(InternalAdminContext.getRole())) {
            throw new AccessDeniedException("Only super_admin can approve or reject refunds.");
        }
    }
}