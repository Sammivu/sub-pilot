package co.subpilot.nomba.service;

import co.subpilot.common.exception.NombaApiException;
import co.subpilot.nomba.NombaApiClient;
import co.subpilot.nomba.NombaApiProperties;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.nomba.dto.VerificationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real implementation of NombaPaymentGateway, backed by Nomba's documented
 * REST API. Active only when subpilot.nomba.mock-mode=false — flip
 * NOMBA_MOCK_MODE=false and supply real credentials to switch from
 * MockNombaGateway to this class with zero changes anywhere else in the
 * codebase (billing engine, dunning, checkout flow all depend only on the
 * NombaPaymentGateway interface).
 *
 * All HTTP plumbing (bearer token attach, accountId header, timeouts, 401
 * retry-with-refresh) is delegated to NombaApiClient, which wraps
 * NombaAuthTokenManager's cached OAuth2 client-credentials token.
 *
 * Endpoints used (api.nomba.com/v1), verified against developer.nomba.com:
 *   POST /checkout/order                       — create a hosted checkout order (initiateCheckout)
 *   POST /checkout/tokenized-card-payment       — charge a previously tokenised card (chargeToken)
 *   GET  /transactions/accounts/single          — verify a transaction's final status (verifyTransaction)
 *   POST /refund                                — issue a refund (initiateRefund) — see caveat in initiateRefund()
 *
 * Amounts: Nomba's API takes amounts as decimal strings in major currency
 * units (e.g. "10000.00" for NGN 10,000), while SubPilot stores everything
 * in minor units (kobo) internally per the PRD. All conversion happens at
 * this boundary — nowhere else in the codebase should know about this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "subpilot.nomba.mock-mode", havingValue = "false")
public class NombaGatewayImpl implements NombaPaymentGateway {

    private final NombaApiClient apiClient;
    private final NombaApiProperties properties;

    @Override
    public CheckoutResponse initiateCheckout(CheckoutRequest request) {
        // Use the caller's merchantReference as Nomba's orderReference when
        // provided — this is what makes the webhook traceable back to a
        // specific subscription/purpose later (see WebhookController). A
        // random UUID here would discard that traceability entirely, since
        // Nomba echoes orderReference back verbatim on the inbound webhook
        // but does NOT echo back arbitrary caller-side metadata blobs in a
        // structure we can rely on for routing.
        String orderReference = (request.merchantReference() != null && !request.merchantReference().isBlank())
                ? request.merchantReference()
                : UUID.randomUUID().toString();

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderReference", orderReference);
        order.put("callbackUrl", request.callbackUrl());
        order.put("customerEmail", request.customerEmail());
        order.put("amount", toMajorUnitsString(request.amountKobo()));
        order.put("currency", request.currency());
        order.put("accountId", properties.getAccountId());
        order.put("allowedPaymentMethods", List.of("Card"));

        Map<String, Object> metaData = new LinkedHashMap<>();
        metaData.put("merchantReference", request.merchantReference());
        if (request.customerName() != null) metaData.put("customerName", request.customerName());
        if (request.customerPhone() != null) metaData.put("customerPhone", request.customerPhone());
        // orderMetaData is sent for Nomba's own dashboard/support visibility
        // only — it is NOT relied upon for webhook routing, since Nomba does
        // not echo it back on the inbound payment_success webhook (see
        // CheckoutPurpose for how routing actually works: via orderReference
        // prefix, which IS guaranteed to round-trip).
        if (request.metadata() != null) metaData.put("note", request.metadata());
        order.put("orderMetaData", metaData);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", order);
        body.put("tokenizeCard", true); // required so future renewals can charge without re-entering card details

        try {
            JsonNode response = apiClient.post("/checkout/order", body);

            if (!apiClient.isSuccessEnvelope(response)) {
                log.warn("Nomba checkout order creation failed: {}", response);
                return new CheckoutResponse(null, orderReference, false);
            }

            String checkoutLink = response.path("data").path("checkoutLink").asText(null);
            if (checkoutLink == null || checkoutLink.isBlank()) {
                log.warn("Nomba checkout response missing checkoutLink: {}", response);
                return new CheckoutResponse(null, orderReference, false);
            }

            log.info("[NOMBA] Checkout order created — ref={} customer={}", orderReference, request.customerEmail());
            return new CheckoutResponse(checkoutLink, orderReference, true);

        } catch (NombaApiException e) {
            log.error("Failed to initiate Nomba checkout for {}: {}", request.customerEmail(), e.getMessage());
            return new CheckoutResponse(null, orderReference, false);
        }
    }

    @Override
    public ChargeResponse chargeToken(ChargeRequest request) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderReference", request.idempotencyKey()); // deterministic — Nomba's own dedup boundary benefits too
        order.put("customerEmail", request.customerEmail());
        order.put("amount", toMajorUnitsString(request.amountKobo()));
        order.put("currency", request.currency());
        order.put("callbackUrl", ""); // not used for merchant-initiated recurring charges
        order.put("accountId", properties.getAccountId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", order);
        body.put("tokenKey", request.cardToken());

        try {
            JsonNode response = apiClient.post("/checkout/tokenized-card-payment", body);

            if (!apiClient.isSuccessEnvelope(response)) {
                String description = response != null ? response.path("description").asText("unknown_error") : "unknown_error";
                return new ChargeResponse(false, null, "charge_rejected", description);
            }

            JsonNode data = response.path("data");
            boolean approved = data.path("status").asBoolean(false);
            String message = data.path("message").asText("");

            if (approved) {
                // Nomba's tokenized-charge response doesn't always carry a separate
                // transaction reference in the success envelope — fall back to the
                // orderReference (idempotency key) we sent, which Nomba echoes
                // back consistently and is unique per attempt.
                String reference = data.has("transactionId")
                        ? data.path("transactionId").asText()
                        : request.idempotencyKey();
                log.info("[NOMBA] Charge SUCCESS — idemKey={} amount={} subscription={}",
                        request.idempotencyKey(), request.amountKobo(), request.subscriptionId());
                return new ChargeResponse(true, reference, null, null);
            } else {
                log.warn("[NOMBA] Charge DECLINED — idemKey={} reason={}", request.idempotencyKey(), message);
                return new ChargeResponse(false, null, "declined", message.isBlank() ? "Charge declined by issuer." : message);
            }

        } catch (NombaApiException e) {
            log.error("Charge failed for subscription={} invoice={}: {}",
                    request.subscriptionId(), request.invoiceId(), e.getMessage());
            // Network/API failure is treated as a failed charge, not a thrown
            // exception — the billing engine and dunning scheduler expect a
            // ChargeResponse either way and will retry on the normal dunning
            // schedule rather than crashing the job.
            return new ChargeResponse(false, null, "gateway_error", "Could not reach Nomba: " + e.getMessage());
        }
    }

    /**
     * Queries Nomba directly for a transaction's final status, independent of
     * whether a webhook for it was ever received. Useful as a reconciliation
     * safety net (e.g. from the checkout return/callback URL handler, or a
     * periodic job that double-checks any PaymentAttempt stuck in
     * "processing" past a reasonable timeout).
     *
     * GET /v1/transactions/accounts/single?orderReference={orderReference}
     */
    @Override
    public VerificationResponse verifyTransaction(String orderReference) {
        try {
            JsonNode response = apiClient.get("/transactions/accounts/single?orderReference=" + orderReference);

            if (!apiClient.isSuccessEnvelope(response)) {
                return new VerificationResponse(false, orderReference, "UNKNOWN");
            }

            JsonNode data = response.path("data");
            String status = data.path("status").asText("UNKNOWN"); // "SUCCESS" or "FAILED" per Nomba docs
            boolean success = "SUCCESS".equalsIgnoreCase(status);

            // Same envelope shape as the inbound payment_success webhook
            // (see WebhookController) — parsed here too so a TSQ sweep can
            // complete the exact same activation flow a webhook would have.
            String transactionId = data.path("transaction").path("transactionId").asText(null);
            String cardToken = data.path("tokenizedCardData").path("tokenKey").asText(null);
            String customerId = data.path("order").path("accountId").asText(null);

            return new VerificationResponse(success, orderReference, status, transactionId, cardToken, customerId);

        } catch (NombaApiException e) {
            log.error("Failed to verify Nomba transaction ref={}: {}", orderReference, e.getMessage());
            return new VerificationResponse(false, orderReference, "VERIFICATION_FAILED");
        }
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signature) {
        // This 2-arg form is kept for interface compatibility, but Nomba's
        // real signature scheme also requires a timestamp header and the
        // already-parsed payload fields (see NombaWebhookSignatureVerifier).
        // WebhookController calls NombaWebhookSignatureVerifier.verify(...)
        // directly when it has the parsed JSON + timestamp available, which
        // is the path actually used in real mode. This fallback exists only
        // so the interface contract is satisfiable without a timestamp.
        log.warn("verifyWebhookSignature(payload, signature) called without a timestamp — " +
                "real Nomba signatures require one and cannot be verified through this method. " +
                "Returning false; WebhookController should call NombaWebhookSignatureVerifier directly.");
        return false;
    }

    @Override
    public RefundResponse initiateRefund(RefundRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", request.originalReference());
        body.put("amount", toMajorUnitsString(request.amountKobo()));
        body.put("merchantTransactionRef", request.idempotencyKey());
        body.put("reason", request.reason() != null ? request.reason() : "Merchant-initiated refund");

        try {
            // NOTE: Nomba's public docs (as of this writing) describe refunds
            // for card-funded transactions as handled via their dashboard /
            // dispute-resolution flow rather than a single documented REST
            // endpoint with a stable path. This call is wired against the
            // most likely path and will need a one-line update if your
            // sandbox account exposes a different route — everything else
            // (idempotency key, amount conversion, response handling) is
            // already correct and won't need to change.
            JsonNode response = apiClient.post("/refund", body);

            if (!apiClient.isSuccessEnvelope(response)) {
                String description = response != null ? response.path("description").asText("refund_rejected") : "refund_rejected";
                return new RefundResponse(false, null, description);
            }

            String reference = response.path("data").path("refundReference").asText(request.idempotencyKey());
            return new RefundResponse(true, reference, null);

        } catch (NombaApiException e) {
            log.error("Refund failed for reference={}: {}", request.originalReference(), e.getMessage());
            return new RefundResponse(false, null, "Could not reach Nomba: " + e.getMessage());
        }
    }

    /**
     * Converts minor units (kobo) to the decimal-string major-unit format
     * Nomba's API expects, e.g. 500000 kobo -> "5000.00" naira.
     */
    private String toMajorUnitsString(long minorUnits) {
        return BigDecimal.valueOf(minorUnits)
                .movePointLeft(2)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }
}