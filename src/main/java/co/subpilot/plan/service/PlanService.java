package co.subpilot.plan.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.plan.BillingInterval;
import co.subpilot.plan.PlanStatus;
import co.subpilot.plan.ProrationPolicy;
import co.subpilot.plan.dto.PlanDtos;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final PlanRepository planRepository;
    private final EventService eventService;
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9-]");

    @Transactional
    public Plan create(String merchantId, PlanDtos.CreatePlanRequest req) {
        if (req.billingInterval() == BillingInterval.custom &&
                (req.intervalValue() == null || req.intervalUnit() == null)) {
            throw new BusinessRuleException("invalid_custom_interval",
                    "Custom billing interval requires intervalValue and intervalUnit.");
        }

        Plan plan = new Plan();
        plan.setMerchantId(merchantId);
        plan.setName(req.name());
        plan.setSlug(generateUniqueSlug(merchantId, req.name()));
        plan.setDescription(req.description());
        plan.setAmount(req.amount());
        plan.setCurrency(req.currency() != null ? req.currency() : "NGN");
        plan.setBillingInterval(req.billingInterval());
        plan.setIntervalValue(req.intervalValue() != null ? req.intervalValue() : 1);
        plan.setIntervalUnit(req.intervalUnit());
        plan.setTrialDays(req.trialDays() != null ? req.trialDays() : 0);
        plan.setProrationPolicy(req.prorationPolicy() != null ? req.prorationPolicy() : ProrationPolicy.none);
        plan.setStatus(PlanStatus.draft);

        plan = planRepository.save(plan);

        eventService.record(merchantId, EventType.PLAN_CREATED, "plan", plan.getId(),
                Map.of("name", plan.getName(), "amount", plan.getAmount()));

        return plan;
    }

    @Transactional
    public Plan update(String merchantId, String planId, PlanDtos.UpdatePlanRequest req) {
        Plan plan = getOwned(merchantId, planId);

        if (req.name() != null) plan.setName(req.name());
        if (req.description() != null) plan.setDescription(req.description());
        if (req.trialDays() != null) plan.setTrialDays(req.trialDays());

        plan = planRepository.save(plan);
        eventService.record(merchantId, EventType.PLAN_UPDATED, "plan", plan.getId(), null);
        return plan;
    }

    @Transactional
    public Plan publish(String merchantId, String planId) {
        Plan plan = getOwned(merchantId, planId);
        if (plan.getStatus() == PlanStatus.archived) {
            throw new BusinessRuleException("invalid_plan_transition", "Cannot publish an archived plan.");
        }
        plan.setStatus(PlanStatus.published);
        plan = planRepository.save(plan);
        eventService.record(merchantId, EventType.PLAN_PUBLISHED, "plan", plan.getId(), null);
        return plan;
    }

    @Transactional
    public Plan archive(String merchantId, String planId) {
        Plan plan = getOwned(merchantId, planId);
        plan.setStatus(PlanStatus.archived);
        plan = planRepository.save(plan);
        eventService.record(merchantId, EventType.PLAN_ARCHIVED, "plan", plan.getId(), null);
        // Existing subscriptions on this plan are unaffected — PRD §6.2
        return plan;
    }

    public Plan getOwned(String merchantId, String planId) {
        return planRepository.findByIdAndMerchantId(planId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("plan", planId));
    }

    public Page<Plan> list(String merchantId, Pageable pageable) {
        return planRepository.findByMerchantId(merchantId, pageable);
    }

    public Page<Plan> search(String merchantId, String q, String status, Pageable pageable) {
        co.subpilot.plan.PlanStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = co.subpilot.plan.PlanStatus.valueOf(status.toLowerCase());
            } catch (IllegalArgumentException e) {
                throw new co.subpilot.common.exception.BusinessRuleException("invalid_status",
                        "status must be one of: draft, published, archived.");
            }
        }
        return planRepository.search(merchantId, (q != null && !q.isBlank()) ? q : null, statusEnum, pageable);
    }

    public Plan getPublishedByMerchantAndPlanSlug(String merchantSlug, String planSlug) {
        return planRepository.findPublishedByMerchantSlugAndPlanSlug(merchantSlug, planSlug)
                .orElseThrow(() -> new ResourceNotFoundException("plan", planSlug));
    }

    private String generateUniqueSlug(String merchantId, String name) {
        String base = NON_ALPHANUMERIC.matcher(
                name.toLowerCase(Locale.ROOT).trim().replace(" ", "-")
        ).replaceAll("");
        if (base.isBlank()) base = "plan";

        String candidate = base;
        int suffix = 1;
        while (planRepository.existsBySlugAndMerchantId(candidate, merchantId)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}