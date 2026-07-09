package co.subpilot.billing;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.dunning.service.DunningTriggerService;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.notification.service.NotificationService;
import co.subpilot.payment.entity.PaymentAttempt;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import co.subpilot.subscription.BillingPeriodCalculator;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.SubscriptionStateMachine;
import co.subpilot.subscription.repository.SubscriptionRepository;
import co.subpilot.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Billing Engine — the core recurring charge orchestrator.
 *
 * Runs every 5 minutes. Protected by ShedLock so only one instance runs at a time
 * across horizontally-scaled deployments.
 *
 * Per cycle:
 * 1. Find all active subscriptions where next_billing_date <= now
 * 2. For each: create idempotent invoice + payment attempt
 * 3. Call Nomba Charge API with stored card token
 * 4. On success: advance next_billing_date, mark invoice paid
 * 5. On failure: move subscription to past_due, start dunning
 *
 * Idempotency key = subscriptionId + periodStart (epoch seconds)
 * If same key already exists in terminal state → skip (prevents double charge)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingEngineJob {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final InvoiceService invoiceService;
    private final EventService eventService;
    private final NombaPaymentGateway nomba;
    private final SubscriptionService subscriptionService;
    private final CustomerRepository customerRepository;
    private final DunningTriggerService dunningTriggerService;
    private final NotificationService notificationService;
    private enum RenewalOutcome { CHARGED, FAILED, SKIPPED }
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String SAVED_CARDS_CACHE_PREFIX = "customer:saved-cards:";

    @Value("${subpilot.billing.job-enabled:true}")
    private boolean jobEnabled;

    public void evictSavedCards(String customerId) {
        redisTemplate.delete(SAVED_CARDS_CACHE_PREFIX + customerId.toLowerCase());
    }

    @Scheduled(fixedDelayString = "${subpilot.billing.interval-ms:300000}")
    @SchedulerLock(name = "billing_engine", lockAtLeastFor = "PT4M", lockAtMostFor = "PT10M")
    public void run() {
        if (!jobEnabled) return;

        List<Subscription> due = subscriptionRepository.findDueForRenewal(Instant.now());
        if (due.isEmpty()) return;

        log.info("Billing engine: {} subscriptions due for renewal", due.size());
        int succeeded = 0, failed = 0, skipped = 0;

        for (Subscription sub : due) {
            try {
                RenewalOutcome outcome = processRenewal(sub);
                switch (outcome) {
                    case CHARGED -> succeeded++;
                    case FAILED -> failed++;
                    case SKIPPED -> skipped++;
                }
            } catch (Exception e) {
                log.error("Billing error for subscription {}: {}", sub.getId(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("Billing engine complete — succeeded={} failed={} skipped={}", succeeded, failed, skipped);
    }

    @Transactional
    public RenewalOutcome processRenewal(Subscription sub) {
        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new RuntimeException("Plan not found: " + sub.getPlanId()));

        // Checked BEFORE any charge attempt — previously this was only
        // checked in advanceBillingDate, AFTER a successful charge, which
        // meant a customer who requested "cancel at period end" (the only
        // option cancelViaPortal offers) was charged for one more full
        // cycle before the cancellation ever took effect. "Cancel at
        // period end" means stop billing, keep access until the period
        // already paid for ends — not "charge once more, then stop."
        if (Boolean.TRUE.equals(sub.isCancelAtPeriodEnd())) {
            finalizeScheduledCancellation(sub);
            return RenewalOutcome.SKIPPED;
        }

        Instant periodStart = sub.getNextBillingDate();
        Instant periodEnd = BillingPeriodCalculator.addInterval(periodStart, plan);

        // ── Idempotency check ──────────────────────────────────────────────
        String idempotencyKey = sub.getId() + ":" + periodStart.getEpochSecond();
        var existingAttempt = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey);
        if (existingAttempt.isPresent() && existingAttempt.get().isTerminal()) {
            log.debug("Skipping duplicate billing for subscription {} (idempotency key already resolved)", sub.getId());
            return RenewalOutcome.SKIPPED;
        }

        // ── Create invoice ─────────────────────────────────────────────────
        Invoice invoice = invoiceService.createOrFind(
                sub.getMerchantId(), sub.getId(), sub.getCustomerId(),
                plan.getAmount(), periodStart, periodEnd);

        if (invoice.isPaid()) {
            log.debug("Invoice already paid for subscription {} period {}", sub.getId(), periodStart);
            advanceBillingDate(sub, plan, periodEnd);
            return RenewalOutcome.CHARGED;
        }

        // ── Create payment attempt ─────────────────────────────────────────
        String cardToken = sub.getNombaCardTokenRef();
        if (cardToken == null || cardToken.isBlank()) {
            log.warn("No card token for subscription {} — cannot charge", sub.getId());
            handleFailure(sub, invoice, null, "no_card_token", "No payment method on file.");
            return RenewalOutcome.FAILED;
        }

        PaymentAttempt newAttempt = new PaymentAttempt();
        newAttempt.setId(com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString());
        newAttempt.setMerchantId(sub.getMerchantId());
        newAttempt.setInvoiceId(invoice.getId());
        newAttempt.setSubscriptionId(sub.getId());
        newAttempt.setIdempotencyKey(idempotencyKey);
        newAttempt.setAmount(plan.getAmount());
        newAttempt.setCurrency(plan.getCurrency());
        newAttempt.setStatus("processing");
        PaymentAttempt attempt = paymentAttemptRepository.save(newAttempt);
        Customer customer = customerRepository.findByIdAndMerchantId(sub.getCustomerId(), sub.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("customer", sub.getCustomerId()));

        // ── Call Nomba Charge API ──────────────────────────────────────────
        NombaPaymentGateway.ChargeResponse charge = nomba.chargeToken(
                new NombaPaymentGateway.ChargeRequest(
                        cardToken, idempotencyKey, plan.getAmount(),
                        plan.getCurrency(), customer.getEmail(),
                        sub.getId(), invoice.getId()
                )
        );

        if (charge.success()) {
            handleSuccess(sub, invoice, attempt, charge.reference(), plan, periodEnd);
            return RenewalOutcome.CHARGED;
        } else {
            handleFailure(sub, invoice, attempt, charge.failureCode(), charge.failureReason());
            return RenewalOutcome.FAILED;
        }
    }

    private void handleSuccess(Subscription sub, Invoice invoice, PaymentAttempt attempt,
                               String nombaRef, Plan plan, Instant periodEnd) {
        // Update payment attempt
        attempt.setStatus("succeeded");
        attempt.setNombaReference(nombaRef);
        attempt.setResolvedAt(Instant.now());
        paymentAttemptRepository.save(attempt);

        // Mark invoice paid — this also applies SubPilot's platform fee
        // exactly once, since the invoice is transitioning into "paid" here.
        invoiceService.markPaid(invoice.getId(), nombaRef, attempt.getId());

        // Advance subscription billing date
        advanceBillingDate(sub, plan, periodEnd);

        eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_RENEWED, "subscription",
                sub.getId(), Map.of("invoiceId", invoice.getId(), "amount", plan.getAmount()));

        // PRD §6.9: subscriber gets a receipt email on every successful renewal charge.
        notificationService.sendPaymentSucceeded(sub, invoice);

        log.info("Renewal succeeded: subscription={} invoice={} ref={}",
                sub.getId(), invoice.getId(), nombaRef);
    }

    private void handleFailure(Subscription sub, Invoice invoice, PaymentAttempt attempt,
                               String code, String reason) {
        if (attempt != null) {
            attempt.setStatus("failed");
            attempt.setFailureCode(code);
            attempt.setFailureReason(reason);
            attempt.setResolvedAt(Instant.now());
            paymentAttemptRepository.save(attempt);
        }

        // Mark invoice failed
        invoiceService.markFailed(invoice.getId());

        // Move subscription to past_due
        SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.past_due);
        sub.setStatus(SubscriptionStatus.past_due);
        subscriptionRepository.save(sub);

        eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_PAST_DUE, "subscription",
                sub.getId(), Map.of("invoiceId", invoice.getId(), "failureCode", code != null ? code : ""));

        // Start dunning
        dunningTriggerService.startDunning(sub, invoice);

        log.warn("Renewal failed: subscription={} invoice={} reason={}", sub.getId(), invoice.getId(), reason);
    }

    private void advanceBillingDate(Subscription sub, Plan plan, Instant periodEnd) {
        sub.setCurrentPeriodStart(sub.getNextBillingDate());
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setNextBillingDate(periodEnd);
        subscriptionRepository.save(sub);
    }

    /**
     * The actual point a "cancel at period end" request finalizes — no
     * charge attempted, subscription transitions straight to cancelled.
     * This is also where the Nomba-side tokenized card is deleted for this
     * cancellation path, mirroring SubscriptionService.cancelResolved's
     * immediate-cancellation path — a subscription shouldn't leave a live,
     * chargeable card token behind on Nomba's side once cancelled, however
     * the cancellation was scheduled.
     */
    private void finalizeScheduledCancellation(Subscription sub) {
        SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.cancelled);
        sub.setStatus(SubscriptionStatus.cancelled);
        sub.setCancelledAt(Instant.now());
        subscriptionRepository.save(sub);

        eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_CANCELLED, "subscription",
                sub.getId(), Map.of("reason", "cancel_at_period_end"));

        deleteNombaCardTokenIfPresent(sub);

        log.info("Subscription cancellation finalized at period end: subscription={}", sub.getId());
    }

    /**
     * Best-effort — a failure here is logged, not thrown, since it must
     * never block the cancellation itself from completing. See
     * NombaPaymentGateway.deleteTokenizedCard's javadoc for the caveat on
     * this endpoint's exact request shape being unverified against Nomba's
     * docs (their page for it didn't surface through search — same
     * pattern as initiateRefund's existing "unverified" flag elsewhere in
     * this codebase).
     */
    private void deleteNombaCardTokenIfPresent(Subscription sub) {
        String tokenRef = sub.getNombaCardTokenRef();
        if (tokenRef == null || tokenRef.isBlank()) return;

        try {
            var result = nomba.deleteTokenizedCard(tokenRef);
            if (!result.success()) {
                log.warn("Failed to delete Nomba tokenized card for subscription={} token={}: {}",
                        sub.getId(), tokenRef, result.failureReason());
            }
            Customer customer = customerRepository.findByIdAndMerchantId(sub.getCustomerId(), sub.getMerchantId())
                    .orElseThrow(() -> new ResourceNotFoundException("customer", sub.getCustomerId()));
            evictSavedCards(customer.getEmail());

        } catch (Exception e) {
            log.error("Error deleting Nomba tokenized card for subscription={} token={}: {}",
                    sub.getId(), tokenRef, e.getMessage(), e);
        }
    }
}