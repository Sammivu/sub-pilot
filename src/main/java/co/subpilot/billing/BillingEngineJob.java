package co.subpilot.billing;

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
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.SubscriptionStateMachine;
import co.subpilot.subscription.repository.SubscriptionRepository;
import co.subpilot.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
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
    private final DunningTriggerService dunningTriggerService;
    private final NotificationService notificationService;

    @Value("${subpilot.billing.job-enabled:true}")
    private boolean jobEnabled;

//    @Scheduled(fixedDelayString = "${subpilot.billing.interval-ms:300000}")
//    @SchedulerLock(name = "billing_engine", lockAtLeastFor = "PT4M", lockAtMostFor = "PT10M")
//    public void run() {
//        if (!jobEnabled) return;
//
////        List<Subscription> due = subscriptionRepository.findDueForRenewal(Instant.now());
//        List<Subscription> due = subscriptionRepository.findDueForBilling(Instant.now());
//        if (due.isEmpty()) return;
//
//        log.info("Billing engine: {} subscriptions due for renewal", due.size());
//        int succeeded = 0, failed = 0;
//
//        for (Subscription sub : due) {
//            try {
//                processRenewal(sub);
//                succeeded++;
//            } catch (Exception e) {
//                log.error("Billing error for subscription {}: {}", sub.getId(), e.getMessage(), e);
//                failed++;
//            }
//        }
//        log.info("Billing engine complete — succeeded={} failed={}", succeeded, failed);
//    }
//
//    @Transactional
//    public void processRenewal(Subscription sub) {
//        Plan plan = planRepository.findById(sub.getPlanId())
//                .orElseThrow(() -> new RuntimeException("Plan not found: " + sub.getPlanId()));
//
//        Instant periodStart = sub.getNextBillingDate();
//        Instant periodEnd = subscriptionService.calculatePeriodEnd(periodStart, plan);
//
//        // ── Idempotency check ──────────────────────────────────────────────
//        String idempotencyKey = sub.getId() + ":" + periodStart.getEpochSecond();
//        var existingAttempt = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey);
//        if (existingAttempt.isPresent() && existingAttempt.get().isTerminal()) {
//            log.debug("Skipping duplicate billing for subscription {} (idempotency key already resolved)", sub.getId());
//            return;
//        }
//
//        // ── Create invoice ─────────────────────────────────────────────────
//        Invoice invoice = invoiceService.createOrFind(
//                sub.getMerchantId(), sub.getId(), sub.getCustomerId(),
//                plan.getAmount(), periodStart, periodEnd);
//
//        if (invoice.isPaid()) {
//            log.debug("Invoice already paid for subscription {} period {}", sub.getId(), periodStart);
//            advanceBillingDate(sub, plan, periodEnd);
//            return;
//        }
//
//        // ── Create payment attempt ─────────────────────────────────────────
//        String cardToken = sub.getNombaCardTokenRef();
//        if (cardToken == null || cardToken.isBlank()) {
//            log.warn("No card token for subscription {} — cannot charge", sub.getId());
//            handleFailure(sub, invoice, null, "no_card_token", "No payment method on file.");
//            return;
//        }
//
//        PaymentAttempt newAttempt = new PaymentAttempt();
//        newAttempt.setId(com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString());
//        newAttempt.setMerchantId(sub.getMerchantId());
//        newAttempt.setInvoiceId(invoice.getId());
//        newAttempt.setSubscriptionId(sub.getId());
//        newAttempt.setIdempotencyKey(idempotencyKey);
//        newAttempt.setAmount(plan.getAmount());
//        newAttempt.setCurrency(plan.getCurrency());
//        newAttempt.setStatus("processing");
//        PaymentAttempt attempt = paymentAttemptRepository.save(newAttempt);
//
//        // ── Call Nomba Charge API ──────────────────────────────────────────
//        NombaPaymentGateway.ChargeResponse charge = nomba.chargeToken(
//                new NombaPaymentGateway.ChargeRequest(
//                        cardToken, idempotencyKey, plan.getAmount(),
//                        plan.getCurrency(), sub.getMerchantId(),
//                        sub.getId(), invoice.getId()
//                )
//        );
//
//        if (charge.success()) {
//            handleSuccess(sub, invoice, attempt, charge.reference(), plan, periodEnd);
//        } else {
//            handleFailure(sub, invoice, attempt, charge.failureCode(), charge.failureReason());
//        }
//    }

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

        // Handle cancel_at_period_end
        if (Boolean.TRUE.equals(sub.isCancelAtPeriodEnd())) {
            sub.setStatus(SubscriptionStatus.cancelled);
            sub.setCancelledAt(Instant.now());
            eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_CANCELLED, "subscription",
                    sub.getId(), Map.of("reason", "cancel_at_period_end"));
        }

        subscriptionRepository.save(sub);
    }
}