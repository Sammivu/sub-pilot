package co.subpilot.payment.service;

import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.payment.PaymentAttemptStatus;
import co.subpilot.payment.entity.PaymentAttempt;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The single choke point through which every charge to Nomba flows.
 *
 * PRD §12.4 idempotency guarantee:
 *   "Before calling Nomba, the system checks for an existing PaymentAttempt
 *    with the same key. If one exists and is in a terminal state, the
 *    operation is skipped."
 *
 * This prevents double charges if the billing job or dunning scheduler
 * executes more than once for the same billing cycle (e.g. due to retry,
 * crash-recovery, or overlapping scheduler runs).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final NombaPaymentGateway nombaPaymentGateway;

    /**
     * Attempts to charge a stored card token for an invoice.
     * idempotencyKey should be deterministic, e.g. "renewal:{subscriptionId}:{periodStart}".
     */
    @Transactional
    public PaymentAttempt charge(
            String merchantId, String invoiceId, String subscriptionId,
            String cardToken, long amount, String currency, String customerEmail,
            String idempotencyKey
    ) {
        // ── Idempotency check ────────────────────────────────────────────────
        var existing = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent() && existing.get().isTerminal()) {
            log.info("Idempotent skip — payment attempt already terminal for key={}", idempotencyKey);
            return existing.get();
        }

        PaymentAttempt attempt = existing.orElseGet(PaymentAttempt::new);
        if (attempt.getId() == null) {
            attempt.setId(UlidCreator.getMonotonicUlid().toString());
            attempt.setMerchantId(merchantId);
            attempt.setInvoiceId(invoiceId);
            attempt.setSubscriptionId(subscriptionId);
            attempt.setIdempotencyKey(idempotencyKey);
            attempt.setAmount(amount);
            attempt.setCurrency(currency);
        }
        attempt.setStatus(PaymentAttemptStatus.PROCESSING);
        attempt.setAttemptedAt(Instant.now());
        paymentAttemptRepository.save(attempt);

        // ── Call Nomba (or mock) ─────────────────────────────────────────────
        var response = nombaPaymentGateway.chargeToken(new NombaPaymentGateway.ChargeRequest(
                cardToken, idempotencyKey, amount, currency, customerEmail, subscriptionId, invoiceId
        ));

        if (response.success()) {
            attempt.setStatus(PaymentAttemptStatus.SUCCEEDED);
            attempt.setNombaReference(response.reference());
        } else {
            attempt.setStatus(PaymentAttemptStatus.FAILED);
            attempt.setFailureCode(response.failureCode());
            attempt.setFailureReason(response.failureReason());
        }
        attempt.setResolvedAt(Instant.now());

        return paymentAttemptRepository.save(attempt);
    }


    public boolean wasAlreadyProcessed(String idempotencyKey) {
        return paymentAttemptRepository.findByIdempotencyKey(idempotencyKey)
                .map(PaymentAttempt::isTerminal)
                .orElse(false);
    }
}