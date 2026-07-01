package co.subpilot.subscription.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.plan.dto.PlanDtos;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.service.PlanService;
import co.subpilot.proration.dto.ProrationDtos;
import co.subpilot.proration.service.ProrationService;
import co.subpilot.subscription.dto.SubscriptionDtos;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final PlanService planService;
    private final ProrationService prorationService;
    private final MerchantRepository merchantRepository;
    private final AuditLogService auditLogService;

    // ── Console (authenticated) ───────────────────────────────────────────────

    @GetMapping("/v1/subscriptions")
    public ResponseEntity<Page<Subscription>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String planId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(subscriptionService.list(status, planId, pageable));
    }

    @GetMapping("/v1/subscriptions/{subscriptionId}")
    public ResponseEntity<Subscription> getById(@PathVariable String subscriptionId) {
        return ResponseEntity.ok(subscriptionService.getById(subscriptionId));
    }

    @PostMapping("/v1/subscriptions/{subscriptionId}/cancel")
    public ResponseEntity<Subscription> cancel(
            @PathVariable String subscriptionId,
            @RequestBody(required = false) SubscriptionDtos.CancelRequest req) {
        String reason = req != null ? req.reason() : null;
        boolean immediate = req == null || req.immediate();
        Subscription before = subscriptionService.getById(subscriptionId);
        Subscription after = subscriptionService.cancel(subscriptionId, reason, immediate);
        auditLogService.record(after.getMerchantId(), AuditAction.SUBSCRIPTION_CANCELLED,
                "subscription", subscriptionId, before.getStatus(), after.getStatus());
        return ResponseEntity.ok(after);
    }

    @PostMapping("/v1/subscriptions/{subscriptionId}/pause")
    public ResponseEntity<Subscription> pause(@PathVariable String subscriptionId) {
        Subscription before = subscriptionService.getById(subscriptionId);
        Subscription after = subscriptionService.pause(subscriptionId);
        auditLogService.record(after.getMerchantId(), AuditAction.SUBSCRIPTION_PAUSED,
                "subscription", subscriptionId, before.getStatus(), after.getStatus());
        return ResponseEntity.ok(after);
    }

    @PostMapping("/v1/subscriptions/{subscriptionId}/resume")
    public ResponseEntity<Subscription> resume(@PathVariable String subscriptionId) {
        Subscription before = subscriptionService.getById(subscriptionId);
        Subscription after = subscriptionService.resume(subscriptionId);
        auditLogService.record(after.getMerchantId(), AuditAction.SUBSCRIPTION_RESUMED,
                "subscription", subscriptionId, before.getStatus(), after.getStatus());
        return ResponseEntity.ok(after);
    }

    @PostMapping("/v1/subscriptions/{subscriptionId}/change-plan")
    public ResponseEntity<ProrationDtos.ChangePlanResponse> changePlan(
            @PathVariable String subscriptionId,
            @Valid @RequestBody ProrationDtos.ChangePlanRequest req) {
        Subscription before = subscriptionService.getById(subscriptionId);
        ProrationDtos.ChangePlanResponse result = prorationService.changePlan(subscriptionId, req.newPlanId());
        auditLogService.record(before.getMerchantId(), AuditAction.SUBSCRIPTION_PLAN_CHANGED,
                "subscription", subscriptionId, before.getPlanId(), req.newPlanId());
        return ResponseEntity.ok(result);
    }

    // ── Public hosted checkout (no auth — subscriber-facing, PRD §6.4) ────────

    // Backs the public plan page itself (PRD §6.3): name, description,
    // price, interval, trial. Returns a slimmer PublicPlanResponse rather
    // than the operator-facing PlanResponse — a subscriber browsing this
    // page has no need to see internal fields like prorationPolicy/status.
    @GetMapping("/v1/public/plans/{merchantSlug}/{planSlug}")
    public ResponseEntity<PlanDtos.PublicPlanResponse> getPublicPlan(
            @PathVariable String merchantSlug, @PathVariable String planSlug) {
        Plan plan = planService.getPublishedByMerchantAndPlanSlug(merchantSlug, planSlug);
        Merchant merchant = merchantRepository.findBySlug(merchantSlug)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantSlug));
        return ResponseEntity.ok(PlanDtos.PublicPlanResponse.from(
                plan, merchant.getBusinessName(), merchantSlug));
    }

    @PostMapping("/v1/public/plans/{merchantSlug}/{planSlug}/checkout")
    public ResponseEntity<SubscriptionDtos.CheckoutInitResponse> initiateCheckout(
            @PathVariable String merchantSlug,
            @PathVariable String planSlug,
            @Valid @RequestBody SubscriptionDtos.CheckoutRequest req) {

        Plan plan = planService.getPublishedByMerchantAndPlanSlug(merchantSlug, planSlug);

        SubscriptionDtos.CheckoutRequest withSlugs = new SubscriptionDtos.CheckoutRequest(
                req.email(), req.fullName(), req.phone(), merchantSlug, planSlug);

        return ResponseEntity.ok(subscriptionService.initiateCheckout(
                plan.getMerchantId(), plan.getId(), withSlugs));
    }
}