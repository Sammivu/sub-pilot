package co.subpilot.nomba;

import co.subpilot.nomba.dto.VerificationResponse;

/**
 * Abstraction over Nomba's payment APIs.
 *
 * Two implementations exist:
 *   - MockNombaGateway  → used during development; controlled via subpilot.nomba.mock-mode=true
 *   - NombaGatewayImpl  → real HTTP calls; activated when keys arrive
 *
 * Swap implementations by flipping NOMBA_MOCK_MODE in application.yml.
 * Zero code changes needed in billing engine, dunning, or anywhere else.
 */
public interface NombaPaymentGateway {

    /**
     * Create a Nomba Checkout session for the subscriber's initial payment.
     * Returns a redirect URL to send the subscriber to.
     */
    CheckoutResponse initiateCheckout(CheckoutRequest request);

    /**
     * Charge a stored tokenised card (merchant-initiated, recurring).
     * Called by the billing engine on renewal.
     */
    ChargeResponse chargeToken(ChargeRequest request);

    /**
     * Verify the HMAC-SHA256 signature on an inbound Nomba webhook.
     */
    boolean verifyWebhookSignature(String rawPayload, String signature);

    /**
     * Initiate a refund via Nomba Transfers API.
     */
    RefundResponse initiateRefund(RefundRequest request);

    /**
     * Queries Nomba directly for a transaction's current status, independent
     * of whether a webhook for it was received. Use this as a reconciliation
     * safety net — e.g. from a checkout return/callback handler, or to
     * double-check a PaymentAttempt stuck in "processing" past a reasonable
     * timeout.
     */
    VerificationResponse verifyTransaction(String orderReference);

    // ── Request / Response Records ────────────────────────────────────────────

    record CheckoutRequest(
            String merchantReference,
            long amountKobo,
            String currency,
            String customerEmail,
            String customerName,
            String customerPhone,
            String callbackUrl,
            String metadata
    ) {}

    record CheckoutResponse(
            String checkoutUrl,
            String reference,
            boolean success
    ) {}

    record ChargeRequest(
            String cardToken,
            String idempotencyKey,
            long amountKobo,
            String currency,
            String customerEmail,
            String subscriptionId,
            String invoiceId
    ) {}

    record ChargeResponse(
            boolean success,
            String reference,
            String failureCode,
            String failureReason
    ) {}

    record RefundRequest(
            String originalReference,
            long amountKobo,
            String currency,
            String idempotencyKey,
            String reason
    ) {}

    record RefundResponse(
            boolean success,
            String reference,
            String failureReason
    ) {}
}