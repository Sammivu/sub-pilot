package co.subpilot.nomba.service;

import co.subpilot.dunning.service.DunningTriggerService;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.payment.PaymentAttemptStatus;
import co.subpilot.payment.entity.PaymentAttempt;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.repository.SubscriptionRepository;
import co.subpilot.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The single place that turns "Nomba says this transaction succeeded/failed"
 * into state changes — regardless of whether that fact arrived via the
 * inbound webhook (WebhookController) or a TSQ reconciliation sweep
 * (NombaReconciliationJob).
 *
 * This is deliberately the ONLY place either caller is allowed to apply
 * these transitions. Both paths are idempotent against each other: whichever
 * one wins the race (webhook arrives first vs. TSQ finds it first) does the
 * work, and the loser's call becomes a no-op because it re-checks current
 * state before mutating anything. There is no scenario where a late webhook
 * arriving after TSQ already resolved something double-charges or
 * double-activates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NombaReconciliationService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;
    private final DunningTriggerService dunningTriggerService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final InvoiceService invoiceService;

    /**
     * A brand-new subscription's initial checkout succeeded — either the
     * webhook told us, or TSQ verified it directly. Safe to call twice: only
     * subscriptions still in trialing/active WITHOUT a card token get
     * activated; a subscription that's already been activated (nombaCardTokenRef
     * already set) is left untouched.
     *
     * IMPORTANT LIMITATION: Nomba's verify-transaction endpoint does not
     * return the card tokenKey (confirmed against Nomba's docs) — it is only
     * ever delivered via the payment_success webhook. So when this is called
     * from the TSQ path with cardToken == null, it means: "payment
     * definitely succeeded, but we still don't have anything to bill this
     * subscriber with going forward." That is NOT the same as "nothing
     * happened" — the subscriber paid. This is logged at WARN specifically
     * so it surfaces for manual follow-up (e.g. contact the subscriber to
     * re-run checkout, or use ops tooling / accountId lookup against Nomba's
     * dashboard) rather than silently vanishing. It should be rare — it only
     * happens if the payment_success webhook for that exact transaction was
     * never delivered even once, ever, which webhook retries make unlikely.
     */
    @Transactional
    public void resolveNewSubscriptionCheckout(String subscriptionId, String cardToken,
                                               String nombaReference, String nombaCustomerId,
                                               String source) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null) {
            log.warn("[{}] new-subscription checkout referenced unknown subscription={}", source, subscriptionId);
            return;
        }
        if (sub.getNombaCardTokenRef() != null) {
            log.debug("[{}] subscription={} already activated — skipping (likely already resolved by webhook or a prior TSQ pass)",
                    source, subscriptionId);
            return;
        }
        if (sub.getStatus() != SubscriptionStatus.trialing && sub.getStatus() != SubscriptionStatus.active) {
            log.debug("[{}] subscription={} status={} no longer eligible for checkout activation — skipping",
                    source, subscriptionId, sub.getStatus());
            return;
        }
        if (cardToken == null) {
            log.warn("[{}] Payment CONFIRMED SUCCEEDED for subscription={} (reference={}) but no card token is " +
                            "available to complete activation — this needs manual follow-up, the subscriber has paid. " +
                            "See NombaReconciliationService javadoc.",
                    source, subscriptionId, nombaReference);
            return;
        }

        log.info("[{}] Activating subscription={} after confirmed checkout (reference={})", source, subscriptionId, nombaReference);
        subscriptionService.activateAfterCheckout(subscriptionId, cardToken, nombaReference, nombaCustomerId);
    }

    /**
     * A card-update (self-cure) checkout succeeded. Safe to call twice: once
     * pendingCardUpdateAt is cleared (inside resolveViaSelfCure), a second
     * call for the same subscription is a normal self-cure re-attempt, not a
     * duplicate — DunningTriggerService.attemptCharge is itself idempotent
     * per PaymentAttempt.idempotencyKey.
     */
    @Transactional
    public void resolveCardUpdateCheckout(String subscriptionId, String cardToken, String nombaCustomerId, String nombaReference, String source) {
        Subscription sub = subscriptionRepository.findById(subscriptionId).orElse(null);
        if (sub == null) {
            log.warn("[{}] card-update checkout referenced unknown subscription={}", source, subscriptionId);
            return;
        }
        if (cardToken == null) {
            log.warn("[{}] card-update checkout succeeded for subscription={} but no card token was present", source, subscriptionId);
            return;
        }

        log.info("[{}] Resolving card update via self-cure for subscription={}", source, subscriptionId);
        dunningTriggerService.resolveViaSelfCure(sub.getSubscriptionToken(), cardToken, nombaReference ,nombaCustomerId);
    }

    /**
     * A PaymentAttempt's outcome is now known (renewal/dunning charge that
     * was stuck in PROCESSING). No-ops if the attempt is already terminal —
     * this is what makes it safe for both the webhook and TSQ to call this
     * for the same attempt.
     */
    @Transactional
    public void resolvePaymentAttempt(String nombaReference, boolean succeeded, String failureCode, String failureReason, String source) {
        paymentAttemptRepository.findByNombaReference(nombaReference).ifPresentOrElse(attempt -> {
            if (attempt.isTerminal()) {
                log.debug("[{}] PaymentAttempt {} already terminal ({}) — skipping", source, attempt.getId(), attempt.getStatus());
                return;
            }
            attempt.setStatus(succeeded ? PaymentAttemptStatus.SUCCEEDED : PaymentAttemptStatus.FAILED);
            if (!succeeded) {
                attempt.setFailureCode(failureCode);
                attempt.setFailureReason(failureReason);
            }
            attempt.setResolvedAt(Instant.now());
            paymentAttemptRepository.save(attempt);

            if (succeeded) {
                invoiceService.markPaid(attempt.getInvoiceId(), nombaReference);
            }

            log.info("[{}] PaymentAttempt {} resolved as {}", source, attempt.getId(), attempt.getStatus());
        }, () -> log.debug("[{}] No PaymentAttempt found for nombaReference={}", source, nombaReference));
    }
}