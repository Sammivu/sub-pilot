package co.subpilot.refund.controller;

import co.subpilot.refund.dto.RefundDtos;
import co.subpilot.refund.entity.Refund;
import co.subpilot.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Auth for everything under /v1/admin/** is handled entirely by
 * AdminApiKeyFilter (static X-Admin-Key header) — see its javadoc for why
 * this is a pragmatic MVP measure rather than real cross-merchant RBAC.
 */
@RestController
@RequestMapping("/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;

    @GetMapping
    public ResponseEntity<List<RefundDtos.RefundResponse>> listPendingApproval() {
        List<RefundDtos.RefundResponse> refunds = refundService.listPendingApproval().stream()
                .map(RefundDtos.RefundResponse::from)
                .toList();
        return ResponseEntity.ok(refunds);
    }

    @PostMapping("/{refundId}/approve")
    public ResponseEntity<RefundDtos.RefundResponse> approve(@PathVariable String refundId) {
        Refund refund = refundService.approve(refundId);
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refund));
    }

    @PostMapping("/{refundId}/reject")
    public ResponseEntity<RefundDtos.RefundResponse> reject(
            @PathVariable String refundId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body != null ? body.get("reason") : null;
        Refund refund = refundService.reject(refundId, reason);
        return ResponseEntity.ok(RefundDtos.RefundResponse.from(refund));
    }
}