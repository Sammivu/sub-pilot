package co.subpilot.nomba.service;

import co.subpilot.nomba.CheckoutPurpose;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.nomba.dto.VerificationResponse;
import co.subpilot.payment.PaymentAttemptStatus;
import co.subpilot.payment.entity.PaymentAttempt;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * TSQ (Transaction Status Query) reconciliation — the fallback for when
 * Nomba's webhook is lost, delayed, or never arrives.
 *
 * Runs periodically and, for anything still sitting unconfirmed past a
 * grace window, calls NombaPaymentGateway.verifyTransaction(orderReference)
 * directly and feeds the result through NombaReconciliationService — the
 * exact same resolver the webhook handler uses. This is what makes it safe
 * against races: if the webhook arrives a second after this job checks, or
 * this job runs a second after the webhook was processed, the second caller
 * always no-ops (see NombaReconciliationService javadoc).
 *
 * Three things get swept, each for a different reason:
 *   1. New-subscription checkouts        — no invoice/card token exists at all until confirmed; worst case if lost.
 *   2. Card-update (self-cure) checkouts — tracked via Subscription.pendingCardUpdateAt (V13), since these
 *                                           subscriptions already have an old card token so "token IS NULL" can't
 *                                           detect them the way new-subscription checkouts can.
 *   3. PaymentAttempt rows stuck PROCESSING — defensive; today's renewal/dunning charge() calls are synchronous, so
 *                                           this should rarely fire, but protects against a future async charge path
 *                                           or a call that raced a container restart mid-flight.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NombaReconciliationJob {

    private final SubscriptionRepository subscriptionRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final NombaPaymentGateway nomba;
    private final NombaReconciliationService reconciliationService;

    /** How long to wait after checkout initiation before treating "no webhook yet" as reconciliation-worthy, not just slow. */
    @Value("${subpilot.nomba.reconciliation.grace-period-minutes:5}")
    private long gracePeriodMinutes;

    @Scheduled(fixedDelayString = "${subpilot.nomba.reconciliation.interval-ms:300000}") // every 5 minutes by default
    @SchedulerLock(name = "nomba_reconciliation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(gracePeriodMinutes, ChronoUnit.MINUTES);

        reconcileNewSubscriptionCheckouts(cutoff);
        reconcileCardUpdateCheckouts(cutoff);
        reconcileStuckPaymentAttempts(cutoff);
    }

    private void reconcileNewSubscriptionCheckouts(Instant cutoff) {
        List<Subscription> pending = subscriptionRepository.findPendingCheckoutConfirmation(cutoff);
        if (pending.isEmpty()) return;

        log.info("TSQ: reconciling {} pending new-subscription checkout(s)", pending.size());

        for (Subscription sub : pending) {
            try {
                String orderReference = CheckoutPurpose.NEW_SUBSCRIPTION_PREFIX + sub.getId();
                VerificationResponse result = nomba.verifyTransaction(orderReference);

                if (result.success()) {
                    reconciliationService.resolveNewSubscriptionCheckout(
                            sub.getId(), result.cardToken(), result.transactionId(), result.customerId(), "tsq");
                } else {
                    log.debug("TSQ: subscription={} checkout still not confirmed (status={}) — will retry next pass",
                            sub.getId(), result.status());
                    // Deliberately no negative action here: Nomba reporting
                    // "not yet successful" is not the same as a confirmed
                    // failure — we just keep checking until it resolves or
                    // the subscriber abandons it (an abandoned checkout
                    // simply never activates, same as today's behaviour).
                }
            } catch (Exception e) {
                log.error("TSQ: failed to reconcile subscription={}: {}", sub.getId(), e.getMessage(), e);
            }
        }
    }

    private void reconcileCardUpdateCheckouts(Instant cutoff) {
        List<Subscription> pending = subscriptionRepository.findPendingCardUpdateConfirmation(cutoff);
        if (pending.isEmpty()) return;

        log.info("TSQ: reconciling {} pending card-update checkout(s)", pending.size());

        for (Subscription sub : pending) {
            try {
                String orderReference = CheckoutPurpose.CARD_UPDATE_PREFIX + sub.getId();
                VerificationResponse result = nomba.verifyTransaction(orderReference);

                if (result.success()) {
                    reconciliationService.resolveCardUpdateCheckout(sub.getId(), result.cardToken(), result.customerId(), "tsq");
                } else {
                    log.debug("TSQ: subscription={} card-update checkout still not confirmed (status={})",
                            sub.getId(), result.status());
                }
            } catch (Exception e) {
                log.error("TSQ: failed to reconcile card update for subscription={}: {}", sub.getId(), e.getMessage(), e);
            }
        }
    }

    private void reconcileStuckPaymentAttempts(Instant cutoff) {
        List<PaymentAttempt> stuck = paymentAttemptRepository.findByStatusAndAttemptedAtBefore(
                PaymentAttemptStatus.PROCESSING, cutoff);
        if (stuck.isEmpty()) return;

        log.info("TSQ: reconciling {} payment attempt(s) stuck in processing", stuck.size());

        for (PaymentAttempt attempt : stuck) {
            try {
                // Renewal/dunning charges use the idempotencyKey as Nomba's
                // orderReference (see NombaGatewayImpl.chargeToken /
                // PaymentService.charge) — verify against that, since a
                // still-processing attempt may not have nombaReference set yet.
                VerificationResponse result = nomba.verifyTransaction(attempt.getIdempotencyKey());
                reconciliationService.resolvePaymentAttempt(
                        attempt.getNombaReference() != null ? attempt.getNombaReference() : attempt.getIdempotencyKey(),
                        result.success(),
                        result.success() ? null : "tsq_verification",
                        result.success() ? null : "TSQ verification reported status=" + result.status(),
                        "tsq"
                );
            } catch (Exception e) {
                log.error("TSQ: failed to reconcile payment attempt={}: {}", attempt.getId(), e.getMessage(), e);
            }
        }
    }
}