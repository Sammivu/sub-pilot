package co.subpilot.dunning.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.dunning.dto.DunningDtos;
import co.subpilot.dunning.entity.DunningCampaign;
import co.subpilot.dunning.entity.DunningStep;
import co.subpilot.dunning.repository.DunningCampaignRepository;
import co.subpilot.dunning.repository.DunningStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DunningCampaignService {

    private final DunningCampaignRepository campaignRepository;
    private final DunningStepRepository stepRepository;

    // ── Campaign CRUD ─────────────────────────────────────────────────────────

    public List<DunningCampaign> listCampaigns(String merchantId) {
        return campaignRepository.findByMerchantId(merchantId);
    }

    public DunningCampaign getCampaign(String merchantId, String campaignId) {
        return campaignRepository.findById(campaignId)
                .filter(c -> c.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new ResourceNotFoundException("dunning_campaign", campaignId));
    }

    @Transactional
    public DunningCampaign updateCampaign(String merchantId, String campaignId,
                                          DunningDtos.UpdateCampaignRequest req) {
        DunningCampaign campaign = getCampaign(merchantId, campaignId);

        if (req.name() != null)                   campaign.setName(req.name());
        if (req.gracePeriodDays() != null)         campaign.setGracePeriodDays(req.gracePeriodDays());
        if (req.maxAttempts() != null)             campaign.setMaxAttempts(req.maxAttempts());
        if (req.cancelAfterExhaustion() != null)   campaign.setCancelAfterExhaustion(req.cancelAfterExhaustion());

        return campaignRepository.save(campaign);
    }

    // ── Step CRUD ─────────────────────────────────────────────────────────────

    @Transactional
    public DunningStep addStep(String merchantId, String campaignId,
                               DunningDtos.CreateStepRequest req) {
        DunningCampaign campaign = getCampaign(merchantId, campaignId);

        if ("send_email".equals(req.action()) && req.emailTemplate() == null) {
            throw new BusinessRuleException("invalid_step",
                    "emailTemplate is required when action is send_email.");
        }

        // Auto-assign step number: max existing + 1, so frontend doesn't
        // need to manage ordering. This also makes steps append-only in
        // terms of numbering — reordering is done purely by changing
        // dayOffset values.
        int nextNumber = campaign.getSteps().stream()
                .mapToInt(DunningStep::getStepNumber)
                .max()
                .orElse(0) + 1;

        DunningStep step = DunningStep.builder()
                .campaignId(campaignId)
                .stepNumber(nextNumber)
                .dayOffset(req.dayOffset())
                .action(req.action())
                .emailTemplate(req.emailTemplate())
                .build();

        // Add to campaign and save via cascade — consistent with how
        // DunningTriggerService.createDefaultCampaign builds the initial steps
        campaign.getSteps().add(step);
        campaignRepository.save(campaign);

        // Re-fetch the saved step to return it with its generated ID
        return stepRepository.findByCampaignIdOrderByStepNumberAsc(campaignId)
                .stream()
                .filter(s -> s.getStepNumber() == nextNumber)
                .findFirst()
                .orElse(step);
    }

    @Transactional
    public DunningStep updateStep(String merchantId, String campaignId, String stepId,
                                  DunningDtos.UpdateStepRequest req) {
        // Verify tenant ownership via campaign
        getCampaign(merchantId, campaignId);

        DunningStep step = stepRepository.findByIdAndCampaignId(stepId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("dunning_step", stepId));

        if (req.dayOffset() != null)    step.setDayOffset(req.dayOffset());
        if (req.action() != null)       step.setAction(req.action());
        if (req.emailTemplate() != null) step.setEmailTemplate(req.emailTemplate());

        return stepRepository.save(step);
    }

    @Transactional
    public void deleteStep(String merchantId, String campaignId, String stepId) {
        DunningCampaign campaign = getCampaign(merchantId, campaignId);

        if (campaign.getSteps().size() <= 1) {
            throw new BusinessRuleException("last_step",
                    "Cannot delete the last step — a dunning campaign must have at least one step.");
        }

        DunningStep step = stepRepository.findByIdAndCampaignId(stepId, campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("dunning_step", stepId));

        // Remove from the campaign's list; cascade takes care of the DB delete
        campaign.getSteps().removeIf(s -> s.getId().equals(stepId));
        stepRepository.delete(step);
    }

    // ── Response mapping ──────────────────────────────────────────────────────

    public DunningDtos.CampaignResponse toResponse(DunningCampaign c) {
        List<DunningDtos.StepResponse> steps = c.getSteps().stream()
                .map(this::toStepResponse)
                .toList();

        return new DunningDtos.CampaignResponse(
                c.getId(), c.getName(), c.getGracePeriodDays(), c.getMaxAttempts(),
                Boolean.TRUE.equals(c.getIsDefault()),
                Boolean.TRUE.equals(c.getCancelAfterExhaustion()),
                steps,
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null
        );
    }

    public DunningDtos.StepResponse toStepResponse(DunningStep s) {
        return new DunningDtos.StepResponse(
                s.getId(), s.getStepNumber(), s.getDayOffset(),
                s.getAction(), s.getEmailTemplate(),
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : null
        );
    }
}