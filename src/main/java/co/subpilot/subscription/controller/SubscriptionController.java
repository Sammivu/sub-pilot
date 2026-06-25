package co.subpilot.subscription.controller;

//import co.subpilot.plan.entity.Plan;
//import co.subpilot.plan.service.PlanService;
//import co.subpilot.subscription.dto.SubscriptionDtos;
//import co.subpilot.subscription.entity.Subscription;
//import co.subpilot.subscription.enums.SubscriptionStatus;
//import co.subpilot.subscription.service.SubscriptionService;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.data.domain.Sort;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.Map;
//
//@RestController
//@RequiredArgsConstructor
//public class SubscriptionController {
//
//    private final SubscriptionService subscriptionService;
//
//    // ── Console (authenticated) ───────────────────────────────────────────────
//
//    @GetMapping("/v1/subscriptions")
//    public ResponseEntity<Page<Subscription>> list(
//            @RequestParam(required = false) String status,
//            @RequestParam(required = false) String planId,
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "20") int size) {
//        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
//        return ResponseEntity.ok(subscriptionService.list(status, planId, pageable));
//    }
//
//    @GetMapping("/v1/subscriptions/{subscriptionId}")
//    public ResponseEntity<Subscription> getById(@PathVariable String subscriptionId) {
//        return ResponseEntity.ok(subscriptionService.getById(subscriptionId));
//    }
//
//    @PostMapping("/v1/subscriptions/{subscriptionId}/cancel")
//    public ResponseEntity<Subscription> cancel(
//            @PathVariable String subscriptionId,
//            @RequestBody(required = false) SubscriptionDtos.CancelRequest req) {
//        String reason = req != null ? req.reason() : null;
//        boolean immediate = req == null || req.immediate();
//        return ResponseEntity.ok(subscriptionService.cancel(subscriptionId, reason, immediate));
//    }
//
//    @PostMapping("/v1/subscriptions/{subscriptionId}/pause")
//    public ResponseEntity<Subscription> pause(@PathVariable String subscriptionId) {
//        return ResponseEntity.ok(subscriptionService.pause(subscriptionId));
//    }
//
//    @PostMapping("/v1/subscriptions/{subscriptionId}/resume")
//    public ResponseEntity<Subscription> resume(@PathVariable String subscriptionId) {
//        return ResponseEntity.ok(subscriptionService.resume(subscriptionId));
//    }
//
//    // ── Public hosted checkout (no auth — subscriber-facing) ──────────────────
//
//    @PostMapping("/v1/public/plans/{merchantSlug}/{planSlug}/checkout")
//    public ResponseEntity<SubscriptionDtos.CheckoutInitResponse> initiateCheckout(
//            @PathVariable String merchantSlug,
//            @PathVariable String planSlug,
//            @Valid @RequestBody SubscriptionDtos.CheckoutRequest req) {
//
//        // Resolve merchant + plan from slugs
//        var planResponse = planService.getPublicPlan(merchantSlug, planSlug);
//        return ResponseEntity.ok(subscriptionService.initiateCheckout(
//                planResponse.merchantId(), planResponse.id(), req));
//    }
//
//    // Injected separately to avoid circular dependency
//    @org.springframework.beans.factory.annotation.Autowired
//    private co.subpilot.plan.service.PlanService planService;
//}


import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.service.PlanService;
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
        return ResponseEntity.ok(subscriptionService.cancel(subscriptionId, reason, immediate));
    }

    @PostMapping("/v1/subscriptions/{subscriptionId}/pause")
    public ResponseEntity<Subscription> pause(@PathVariable String subscriptionId) {
        return ResponseEntity.ok(subscriptionService.pause(subscriptionId));
    }

    @PostMapping("/v1/subscriptions/{subscriptionId}/resume")
    public ResponseEntity<Subscription> resume(@PathVariable String subscriptionId) {
        return ResponseEntity.ok(subscriptionService.resume(subscriptionId));
    }

    // ── Public hosted checkout (no auth — subscriber-facing, PRD §6.4) ────────

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
