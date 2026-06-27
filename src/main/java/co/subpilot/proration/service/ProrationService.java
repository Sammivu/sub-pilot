package co.subpilot.proration.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.invoice.InvoiceStatus;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.payment.entity.PaymentAttempt;
import co.subpilot.payment.service.PaymentService;
import co.subpilot.plan.ProrationPolicy;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import co.subpilot.proration.ProrationCalculator;
import co.subpilot.proration.dto.ProrationDtos;
import co.subpilot.proration.entity.ProrationRecord;
import co.subpilot.proration.repository.ProrationRecordRepository;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Orchestrates mid-cycle plan changes (PRD §9).
 *
 * This is the layer that decides WHAT TO DO with the numbers
 * ProrationCalculator produces — charge now, credit forward, or defer
 * entirely — based on the new plan's configured ProrationPolicy. The
 * calculator itself stays a pure function; all the side effects (DB writes,
 * Nomba calls, event emission) live here.
 *
 * Policy semantics (PRD §9, "Proration Policy (per plan)" table):
 *   none   -> no proration at all. Plan changes at next cycle, full price,
 *             no immediate charge or credit. Still records a ProrationRecord
 *             with zero amounts, for audit completeness.
 *   credit -> credit unused days on upgrade, charge the difference
 *             immediately. This is the standard "upgrade now, pay the
 *             prorated difference today" experience.
 *   charge -> always charge/credit the difference immediately, on both
 *             upgrades AND downgrades.
 *
 * Which plan's policy governs a change — the OLD plan's or the NEW plan's?
 * We use the NEW plan's policy, since that's the plan the subscriber is
 * moving TO and therefore the one whose pricing terms should govern how the
 * transition is billed. This mirrors how Stripe treats the destination
 * price's proration behaviour as authoritative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProrationService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final ProrationRecordRepository prorationRecordRepository;
    private final EventService eventService;

    @Transactional
    public ProrationDtos.ChangePlanResponse changePlan(String subscriptionId, String newPlanId) {
        String merchantId = TenantContext.requireMerchantId();

        Subscription sub = subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("subscription", subscriptionId));

        return changePlanResolved(sub, merchantId, newPlanId);
    }

    /**
     * Portal variant — the subscriber has already been authenticated by
     * possession of their opaque subscription_token, so the Subscription is
     * passed in already-resolved rather than looked up via TenantContext
     * (which portal requests never populate, since there's no merchant JWT
     * on a token-based request).
     */
    @Transactional
    public ProrationDtos.ChangePlanResponse changePlanViaPortal(Subscription sub, String newPlanId) {
        return changePlanResolved(sub, sub.getMerchantId(), newPlanId);
    }

    private ProrationDtos.ChangePlanResponse changePlanResolved(Subscription sub, String merchantId, String newPlanId) {

        // Plan changes only make sense for a subscription currently being
        // billed normally. A trialing subscription hasn't started a paid
        // cycle yet (nothing to prorate against); past_due/paused/
        // cancelled/expired subscriptions need to resolve their current
        // state first — changing the plan underneath a failing or
        // suspended subscription would produce a confusing proration
        // result (what's "unused" on a cycle that never got paid for?).
        if (sub.getStatus() != SubscriptionStatus.active) {
            throw new BusinessRuleException("invalid_subscription_state",
                    "Plan changes are only allowed while the subscription is active. Current status: " + sub.getStatus());
        }

        Plan oldPlan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("plan", sub.getPlanId()));
        Plan newPlan = planRepository.findByIdAndMerchantId(newPlanId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("plan", newPlanId));

        if (oldPlan.getId().equals(newPlan.getId())) {
            throw new BusinessRuleException("same_plan", "Subscription is already on this plan.");
        }
        if (!newPlan.isPublished()) {
            throw new BusinessRuleException("plan_not_published", "Cannot move a subscription to an unpublished plan.");
        }

        Instant now = Instant.now();
        ProrationCalculator.ProrationResult calc = ProrationCalculator.calculate(sub, oldPlan, newPlan, now);

        ProrationPolicy policy = newPlan.getProrationPolicy();
        boolean isUpgrade = ProrationCalculator.isUpgrade(oldPlan, newPlan);

        String paymentStatus;
        boolean chargedImmediately = false;
        boolean takesEffectNextCycle;
        String invoiceIdForRecord = null;

        switch (policy) {
            case none -> {
                // PRD §9: "No proration; change takes effect at next cycle."
                // We apply the plan change immediately (so the subscriber
                // sees their new plan right away) but charge nothing extra
                // today — the new amount simply takes over from the next
                // renewal onward. No credit, no immediate debit.
                sub.setPlanId(newPlan.getId());
                takesEffectNextCycle = true;
                paymentStatus = "deferred_to_next_cycle";
            }

            case credit -> {
                // PRD §9: "Credit unused days; charge difference immediately on upgrade."
                // Downgrades under this policy apply the credit to the next
                // invoice rather than triggering an immediate charge — there
                // is no "difference" to charge on a downgrade, only credit
                // to carry forward via the next invoice's proration_note.
                if (isUpgrade && calc.netChargeToday() > 0) {
                    Invoice invoice = chargeProrationNow(sub, oldPlan, newPlan, calc.netChargeToday(), now);
                    invoiceIdForRecord = invoice.getId();
                    chargedImmediately = InvoiceStatus.PAID.equals(invoice.getStatus());
                    paymentStatus = chargedImmediately ? "charged" : "charge_failed";
                } else {
                    paymentStatus = "credited_forward";
                }
                sub.setPlanId(newPlan.getId());
                takesEffectNextCycle = false;
            }

            case charge -> {
                // PRD §9: "Always charge/credit difference immediately."
                // A downgrade's net is a credit, not a charge, so there's
                // nothing to charge Nomba for in that case — the credit is
                // simply recorded for the next invoice. The "charge" in the
                // policy name refers to always settling now, not always
                // being a debit.
                if (calc.netChargeToday() > 0) {
                    Invoice invoice = chargeProrationNow(sub, oldPlan, newPlan, calc.netChargeToday(), now);
                    invoiceIdForRecord = invoice.getId();
                    chargedImmediately = InvoiceStatus.PAID.equals(invoice.getStatus());
                    paymentStatus = chargedImmediately ? "charged" : "charge_failed";
                } else {
                    paymentStatus = "credited_forward";
                }
                sub.setPlanId(newPlan.getId());
                takesEffectNextCycle = false;
            }

            default -> throw new IllegalStateException("Unhandled proration policy: " + policy);
        }

        subscriptionRepository.save(sub);

        ProrationRecord record = prorationRecordRepository.save(ProrationRecord.builder()
                .merchantId(merchantId)
                .subscriptionId(sub.getId())
                .previousPlanId(oldPlan.getId())
                .newPlanId(newPlan.getId())
                .creditAmount(calc.creditAmount())
                .chargeAmount(calc.newPlanProrated())
                .invoiceId(invoiceIdForRecord)
                .build());

        eventService.emit(merchantId, EventType.PRORATION_APPLIED, "subscription", sub.getId(), Map.of(
                "previousPlanId", oldPlan.getId(),
                "newPlanId", newPlan.getId(),
                "netChargeToday", calc.netChargeToday(),
                "netCreditForward", calc.netCreditForward(),
                "policy", policy.name(),
                "prorationRecordId", record.getId()
        ));

        log.info("Plan change applied: subscription={} {}->{} policy={} netCharge={} netCredit={}",
                sub.getId(), oldPlan.getId(), newPlan.getId(), policy, calc.netChargeToday(), calc.netCreditForward());

        return new ProrationDtos.ChangePlanResponse(
                sub.getId(), oldPlan.getId(), newPlan.getId(),
                calc.cycleDays(), calc.unusedDays(), calc.creditAmount(), calc.newPlanProrated(),
                calc.netChargeToday(), calc.netCreditForward(),
                chargedImmediately, takesEffectNextCycle, paymentStatus
        );
    }

    /**
     * Creates a standalone invoice for the immediate proration charge and
     * attempts to charge the subscriber's stored card for it. Uses the same
     * idempotent PaymentService.charge() path as regular billing, keyed to
     * this specific invoice, so a duplicate plan-change request (e.g. a
     * retried API call) never double charges.
     *
     * The invoice itself is deduplicated by an explicit key derived from
     * (subscriptionId, oldPlanId, newPlanId, currentPeriodStart) — NOT by
     * "now", since two retries of the same logical plan-change request
     * land at different instants and "now" would defeat idempotency at the
     * exact moment it matters most (a double-submitted upgrade click).
     * The dedup key changes only when the subscriber makes a genuinely
     * different change (different plan pair) or the cycle rolls over.
     */
    private Invoice chargeProrationNow(Subscription sub, Plan oldPlan, Plan newPlan, long amount, Instant now) {
        Customer customer = customerRepository.findById(sub.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("customer", sub.getCustomerId()));

        String dedupKey = "proration:" + sub.getId() + ":" + oldPlan.getId() + ":" + newPlan.getId()
                + ":" + sub.getCurrentPeriodStart();

        Invoice invoice = invoiceService.generateForProrationDedupKey(
                sub.getMerchantId(), sub.getId(), sub.getCustomerId(),
                amount, newPlan.getCurrency(), sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd(), now,
                dedupKey, "Proration charge for upgrade to " + newPlan.getName()
        );

        // Invoice already settled by an earlier attempt at this exact change — nothing more to do.
        if (InvoiceStatus.PAID.equals(invoice.getStatus()) || InvoiceStatus.FAILED.equals(invoice.getStatus())) {
            return invoice;
        }

        if (sub.getNombaCardTokenRef() == null) {
            invoiceService.markFailed(invoice.getId());
            log.warn("Proration charge skipped — no stored card token for subscription {}", sub.getId());
            return invoice;
        }

        // Payment-level idempotency key is derived from the SAME dedup key as
        // the invoice, so the two layers of idempotency stay in lockstep —
        // a retried request resolves to the same invoice AND the same
        // payment attempt, not just one or the other.
        String idempotencyKey = dedupKey + ":" + invoice.getId();

        PaymentAttempt attempt = paymentService.charge(
                sub.getMerchantId(), invoice.getId(), sub.getId(),
                sub.getNombaCardTokenRef(), amount, newPlan.getCurrency(), customer.getEmail(),
                idempotencyKey
        );

        if (attempt.isSucceeded()) {
            invoiceService.markPaid(invoice.getId(), attempt.getNombaReference(), attempt.getId());
        } else {
            invoiceService.markFailed(invoice.getId());
        }

        return invoiceService.getOwned(sub.getMerchantId(), invoice.getId());
    }
}