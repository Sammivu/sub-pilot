package co.subpilot.dunning.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.dunning.dto.DunningDtos;
import co.subpilot.dunning.entity.DunningCampaign;
import co.subpilot.dunning.entity.DunningStep;
import co.subpilot.dunning.service.DunningCampaignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dunning campaign configuration (Gap 3).
 *
 * Exposes exactly the six endpoints specified in the Gap document:
 *   GET    /v1/dunning/campaigns
 *   GET    /v1/dunning/campaigns/{id}
 *   PATCH  /v1/dunning/campaigns/{id}
 *   POST   /v1/dunning/campaigns/{id}/steps
 *   PATCH  /v1/dunning/campaigns/{id}/steps/{stepId}
 *   DELETE /v1/dunning/campaigns/{id}/steps/{stepId}
 *
 * No new migrations needed — DunningCampaign and DunningStep tables were
 * created in V4. All business logic lives in DunningCampaignService.
 *
 * Every merchant automatically gets one default campaign (created by
 * DunningTriggerService.createDefaultCampaign during their first failed
 * payment), so GET /v1/dunning/campaigns will always return at least one
 * result for a merchant that has had at least one subscription fail.
 */
@RestController
@RequestMapping("/v1/dunning/campaigns")
@RequiredArgsConstructor
public class DunningCampaignController {

    private final DunningCampaignService campaignService;
    private final AuditLogService auditLogService;

    // ── Campaign endpoints ────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<DunningDtos.CampaignResponse>> list() {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(
                campaignService.listCampaigns(merchantId).stream()
                        .map(campaignService::toResponse)
                        .toList()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<DunningDtos.CampaignResponse> get(@PathVariable String id) {
        String merchantId = TenantContext.requireMerchantId();
        DunningCampaign campaign = campaignService.getCampaign(merchantId, id);
        return ResponseEntity.ok(campaignService.toResponse(campaign));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<DunningDtos.CampaignResponse> update(@PathVariable String id,
                                                               @Valid @RequestBody DunningDtos.UpdateCampaignRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        DunningDtos.CampaignResponse before = campaignService.toResponse(
                campaignService.getCampaign(merchantId, id));
        DunningCampaign updated = campaignService.updateCampaign(merchantId, id, req);
        DunningDtos.CampaignResponse after = campaignService.toResponse(updated);
        auditLogService.record(merchantId, AuditAction.DUNNING_CAMPAIGN_UPDATED,
                "dunning_campaign", id, before, after);
        return ResponseEntity.ok(after);
    }

    // ── Step endpoints ────────────────────────────────────────────────────────

    @PostMapping("/{id}/steps")
    public ResponseEntity<DunningDtos.StepResponse> addStep(@PathVariable String id,
                                                            @Valid @RequestBody DunningDtos.CreateStepRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        DunningStep step = campaignService.addStep(merchantId, id, req);
        DunningDtos.StepResponse response = campaignService.toStepResponse(step);
        auditLogService.recordCreation(merchantId, AuditAction.DUNNING_STEP_CREATED, "dunning_step", step.getId(), response);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/steps/{stepId}")
    public ResponseEntity<DunningDtos.StepResponse> updateStep(@PathVariable String id, @PathVariable String stepId,
            @Valid @RequestBody DunningDtos.UpdateStepRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        DunningDtos.StepResponse before = campaignService.toStepResponse(campaignService.updateStep(merchantId, id, stepId,
                        new DunningDtos.UpdateStepRequest(null, null, null)) // fetch only
        );
        DunningStep step = campaignService.updateStep(merchantId, id, stepId, req);
        DunningDtos.StepResponse after = campaignService.toStepResponse(step);
        auditLogService.record(merchantId, AuditAction.DUNNING_STEP_UPDATED, "dunning_step", stepId, before, after);
        return ResponseEntity.ok(after);
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    public ResponseEntity<Map<String, String>> deleteStep(@PathVariable String id, @PathVariable String stepId) {
        String merchantId = TenantContext.requireMerchantId();
        // Capture before-snapshot before deletion
        DunningDtos.StepResponse before = campaignService.toStepResponse(campaignService.updateStep(merchantId, id, stepId,
                        new DunningDtos.UpdateStepRequest(null, null, null)) // fetch only
        );
        campaignService.deleteStep(merchantId, id, stepId);
        auditLogService.recordDeletion(merchantId, AuditAction.DUNNING_STEP_DELETED, "dunning_step", stepId, before);
        return ResponseEntity.ok(Map.of("message", "Step deleted."));
    }
}