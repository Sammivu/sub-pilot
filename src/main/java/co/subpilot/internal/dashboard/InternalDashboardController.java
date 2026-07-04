package co.subpilot.internal.dashboard;

import co.subpilot.merchant.MerchantStatus;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.refund.RefundStatus;
import co.subpilot.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers "how does an admin know they have something to act on" without
 * requiring them to remember to apply a filter — the frontend polls this
 * on dashboard load (and can badge/poll it periodically) rather than an
 * admin having to think to check GET /v1/internal/merchants?status=under_review
 * or GET /v1/internal/admin/refunds on their own initiative. Not a
 * notification system (no email/push in V1 — see the admin spec's Out of
 * Scope list) — just a single cheap counts endpoint the dashboard shell
 * can call on every page load.
 */
@RestController
@RequestMapping("/v1/internal/dashboard")
@RequiredArgsConstructor
public class InternalDashboardController {

    private final MerchantRepository merchantRepository;
    private final RefundRepository refundRepository;

    public record SummaryResponse(long pendingMerchantActivations, long pendingRefundApprovals) {}

    @GetMapping("/summary")
    public ResponseEntity<SummaryResponse> summary() {
        long pendingMerchants = merchantRepository.countByStatus(MerchantStatus.UNDER_REVIEW);
        long pendingRefunds = refundRepository.findByStatus(RefundStatus.PENDING_APPROVAL).size();
        return ResponseEntity.ok(new SummaryResponse(pendingMerchants, pendingRefunds));
    }
}