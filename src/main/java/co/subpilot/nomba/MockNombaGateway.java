package co.subpilot.nomba;

import co.subpilot.nomba.dto.VerificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock implementation of NombaPaymentGateway.
 *
 * Active when subpilot.nomba.mock-mode=true (the default in dev).
 *
 * Behaviour:
 *   - initiateCheckout: always succeeds, returns a fake checkout URL
 *   - chargeToken: succeeds by default; set chargeSuccessRate < 1.0 to simulate failures
 *   - verifyWebhookSignature: always returns true in mock mode
 *   - initiateRefund: always succeeds
 *   - verifyTransaction: always reports SUCCESS in mock mode
 *
 * This lets you build and demo the full lifecycle — billing engine, dunning,
 * self-cure — without real Nomba credentials.
 *
 * To simulate a failure scenario for the demo:
 *   Set MOCK_CHARGE_SUCCESS_RATE=0.0 to force all charges to fail.
 *   Set MOCK_CHARGE_SUCCESS_RATE=0.5 for 50% success rate.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "subpilot.nomba.mock-mode", havingValue = "true", matchIfMissing = true)
public class MockNombaGateway implements NombaPaymentGateway {

    // Controls charge success. 1.0 = always succeed. 0.0 = always fail.
    // Change this for demo scenarios without redeploying.
    private volatile double chargeSuccessRate = 1.0;

    // Counter for alternating success/failure in demo mode
    private final AtomicInteger chargeCounter = new AtomicInteger(0);

    @Override
    public CheckoutResponse initiateCheckout(CheckoutRequest request) {
        String reference = "mock_checkout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String checkoutUrl = "http://localhost:5173/mock-checkout?ref=" + reference
                + "&amount=" + request.amountKobo()
                + "&email=" + request.customerEmail();

        log.info("[MOCK NOMBA] Checkout initiated — ref={} amount={} customer={}",
                reference, request.amountKobo(), request.customerEmail());

        return new CheckoutResponse(checkoutUrl, reference, true);
    }

    @Override
    public ChargeResponse chargeToken(ChargeRequest request) {
        int count = chargeCounter.incrementAndGet();
        boolean shouldSucceed = (chargeSuccessRate >= 1.0) || (Math.random() < chargeSuccessRate);

        if (shouldSucceed) {
            String reference = "mock_charge_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            log.info("[MOCK NOMBA] Charge SUCCESS — ref={} amount={} subscription={} invoice={}",
                    reference, request.amountKobo(), request.subscriptionId(), request.invoiceId());
            return new ChargeResponse(true, reference, null, null);
        } else {
            log.warn("[MOCK NOMBA] Charge FAILED — insufficient_funds subscription={} invoice={}",
                    request.subscriptionId(), request.invoiceId());
            return new ChargeResponse(false, null, "insufficient_funds", "Card declined — insufficient funds.");
        }
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signature) {
        log.debug("[MOCK NOMBA] Webhook signature check bypassed in mock mode");
        return true;
    }

    @Override
    public RefundResponse initiateRefund(RefundRequest request) {
        String reference = "mock_refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        log.info("[MOCK NOMBA] Refund initiated — ref={} amount={}", reference, request.amountKobo());
        return new RefundResponse(true, reference, null);
    }

    @Override
    public VerificationResponse verifyTransaction(String orderReference) {
        log.info("[MOCK NOMBA] Transaction verification — ref={} (always reports SUCCESS in mock mode)", orderReference);
        String transactionId = "mock_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String cardToken = "mock_token_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String customerId = "mock_customer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new VerificationResponse(true, orderReference, "SUCCESS", transactionId, cardToken, customerId);
    }

    // ── Demo controls (call these from a test/demo endpoint if needed) ────────

    public void setChargeSuccessRate(double rate) {
        this.chargeSuccessRate = Math.max(0.0, Math.min(1.0, rate));
        log.info("[MOCK NOMBA] Charge success rate set to {}%", (int)(rate * 100));
    }

    public double getChargeSuccessRate() {
        return chargeSuccessRate;
    }
}