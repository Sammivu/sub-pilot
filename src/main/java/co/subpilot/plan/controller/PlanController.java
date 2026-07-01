package co.subpilot.plan.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.plan.dto.PlanDtos;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Maps to /app/plans and /app/plans/:id in the frontend.
 */
@RestController
@RequestMapping("/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final MerchantRepository merchantRepository;
    private final AuditLogService auditLogService;

    @Value("${subpilot.frontend.base-url}")
    private String frontendBaseUrl;

    @Operation(summary = "Create Plan", description = "Creates a new subscription plan")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Plan created"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @PostMapping
    public ResponseEntity<PlanDtos.PlanResponse> create(@Valid @RequestBody PlanDtos.CreatePlanRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        Plan plan = planService.create(merchantId, req);
        auditLogService.recordCreation(merchantId, AuditAction.PLAN_CREATED, "plan", plan.getId(),
                toResponse(plan, merchantId));
        return ResponseEntity.ok(toResponse(plan, merchantId));
    }

    @Operation(summary = "Get list Plan", description = "Get list of  subscription plans")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Plan retrieved"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    @GetMapping
    public ResponseEntity<Page<PlanDtos.PlanResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int perPage
    ) {
        String merchantId = TenantContext.requireMerchantId();
        Page<Plan> plans = planService.list(merchantId, PageRequest.of(page, Math.min(perPage, 100)));
        return ResponseEntity.ok(plans.map(p -> toResponse(p, merchantId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlanDtos.PlanResponse> get(@PathVariable String id) {
        String merchantId = TenantContext.requireMerchantId();
        Plan plan = planService.getOwned(merchantId, id);
        return ResponseEntity.ok(toResponse(plan, merchantId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PlanDtos.PlanResponse> update(
            @PathVariable String id, @Valid @RequestBody PlanDtos.UpdatePlanRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        PlanDtos.PlanResponse before = toResponse(planService.getOwned(merchantId, id), merchantId);
        Plan plan = planService.update(merchantId, id, req);
        PlanDtos.PlanResponse after = toResponse(plan, merchantId);
        auditLogService.record(merchantId, AuditAction.PLAN_UPDATED, "plan", id, before, after);
        return ResponseEntity.ok(after);
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<PlanDtos.PlanResponse> publish(@PathVariable String id) {
        String merchantId = TenantContext.requireMerchantId();
        PlanDtos.PlanResponse before = toResponse(planService.getOwned(merchantId, id), merchantId);
        Plan plan = planService.publish(merchantId, id);
        PlanDtos.PlanResponse after = toResponse(plan, merchantId);
        auditLogService.record(merchantId, AuditAction.PLAN_PUBLISHED, "plan", id, before, after);
        return ResponseEntity.ok(after);
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<PlanDtos.PlanResponse> archive(@PathVariable String id) {
        String merchantId = TenantContext.requireMerchantId();
        PlanDtos.PlanResponse before = toResponse(planService.getOwned(merchantId, id), merchantId);
        Plan plan = planService.archive(merchantId, id);
        PlanDtos.PlanResponse after = toResponse(plan, merchantId);
        auditLogService.record(merchantId, AuditAction.PLAN_ARCHIVED, "plan", id, before, after);
        return ResponseEntity.ok(after);
    }

    // DELETE maps to archive per PRD §10 ("DELETE archives a plan")
    @DeleteMapping("/{id}")
    public ResponseEntity<PlanDtos.PlanResponse> delete(@PathVariable String id) {
        return archive(id);
    }

    // Note: the public, unauthenticated plan-details endpoint
    // (GET /v1/public/plans/:merchantSlug/:planSlug) lives in
    // SubscriptionController, not here — this controller has a class-level
    // @RequestMapping("/v1/plans") prefix, so a method here can't expose an
    // absolute /v1/public/... path without Spring concatenating both
    // prefixes into something wrong. SubscriptionController already has no
    // class-level mapping and hosts the matching public checkout endpoint,
    // so the two public plan-page routes stay together there.

    private PlanDtos.PlanResponse toResponse(Plan plan, String merchantId) {
        String merchantSlug = merchantRepository.findById(merchantId)
                .map(Merchant::getSlug)
                .orElse("merchant");
        return PlanDtos.PlanResponse.from(plan, merchantSlug, frontendBaseUrl);
    }
}