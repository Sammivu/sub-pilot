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
import java.util.*;

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
 * Endpoints used, verified against developer.nomba.com — base-url must now
 * be HOST ONLY (e.g. https://api.nomba.com or https://sandbox.nomba.com),
 * since every path below specifies its own version/environment prefix
 * explicitly rather than relying on baseUrl to supply a fixed /v1:
 *   POST /v1/auth/token/issue (or /sandbox equiv. — same path both envs)  — OAuth2 token (NombaAuthTokenManager)
 *   POST {checkoutBasePath}/order                    — create a hosted checkout order (initiateCheckout)
 *                                                        checkoutBasePath = /v1/checkout (prod) or /sandbox/checkout (sandbox)
 *   POST {checkoutBasePath}/tokenized-card-payment    — charge a previously tokenised card (chargeToken)
 *   GET  /v1/transactions/accounts/single             — verify a checkout (?orderReference=) or transfer (?transactionRef=) status
 *   POST /v1/transfers/wallet                         — Nomba-to-Nomba wallet transfer (initiateTransfer)
 *   POST /v2/transfers/bank                           — external bank account transfer (initiateBankTransfer) — note the /v2
 *   POST /v1/refund                                   — issue a refund (initiateRefund) — see caveat in initiateRefund()
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

    /**
     * Sandbox and production diverge on checkout-family paths specifically
     * (confirmed against developer.nomba.com/docs/products/accept-payment/sandbox-testing):
     * sandbox uses /sandbox/checkout/..., production uses /v1/checkout/....
     * Auth, transfers, and account-transaction lookups do NOT have this
     * split — they stay under /v1 (or /v2 for bank transfers) regardless of
     * properties.isSandbox(). base-url should now be HOST ONLY
     * (e.g. https://api.nomba.com or https://sandbox.nomba.com) — every
     * call below specifies its own full versioned path explicitly, since a
     * single fixed baseUrl-includes-/v1 scheme can't represent /v1 and /v2
     * (bank transfers) coexisting.
     */
    private String checkoutBasePath() {
        return properties.isSandbox() ? "/sandbox/checkout" : "/v1/checkout";
    }

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
        order.put("accountId", properties.getSubAccountId());
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
            JsonNode response = apiClient.post(checkoutBasePath() + "/order", body);

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
        order.put("accountId", properties.getSubAccountId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order", order);
        body.put("tokenKey", request.cardToken());

        try {
            // Assumes the same sandbox/production checkout-path split as
            // order creation — Nomba's sandbox docs confirm this pattern
            // for /checkout/order and /checkout/transaction explicitly, but
            // don't explicitly list tokenized-card-payment. Flagging as an
            // assumption, not a confirmed fact, same spirit as the existing
            // caveat on initiateRefund below.
            JsonNode response = apiClient.post(checkoutBasePath() + "/tokenized-card-payment", body);

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
    /**
     * Nomba's real response schema for this endpoint (confirmed against
     * https://developer.nomba.com/docs/products/accept-payment/verify-transactions)
     * is flat and does NOT include tokenizedCardData or an accountId field:
     *
     *   { "code": "00", "data": { "id": "...", "status": "SUCCESS", "amount": "...",
     *       "onlineCheckoutOrderReference": "...", "onlineCheckoutCustomerEmail": "...", ... } }
     *
     * Critically: the card tokenKey is delivered ONLY via the payment_success
     * webhook, never via this endpoint. That means TSQ can confirm a
     * checkout's payment status, but cannot by itself recover a card token
     * for a genuinely-lost webhook — see NombaReconciliationService for how
     * that's handled (flagged for follow-up rather than silently dropped).
     */
    @Override
    public VerificationResponse verifyTransaction(String orderReference) {
        try {
            JsonNode response = apiClient.get("/v1/transactions/accounts/single?orderReference=" + orderReference);

            if (!apiClient.isSuccessEnvelope(response) || response.path("data").isNull()) {
                return new VerificationResponse(false, orderReference, "Transaction doesn't exist yet or checkout abandoned");
            }

            JsonNode data = response.path("data");
            String status = data.path("status").asText("UNKNOWN"); // "SUCCESS" or "FAILED"
            boolean success = "SUCCESS".equalsIgnoreCase(status);
            String transactionId = data.path("id").asText(null); // Nomba's transaction id/reference

            // No card token or customer id available from this endpoint —
            // deliberately left null. See javadoc above.
            return new VerificationResponse(success, orderReference, status, transactionId, null, null);

        } catch (NombaApiException e) {
            log.error("Failed to verify Nomba transaction ref={}: {}", orderReference, e.getMessage());
            return new VerificationResponse(false, orderReference, "VERIFICATION_FAILED");
        }
    }

    /**
     * Confirmed against https://developer.nomba.com/products/transfers/transfer-between-accounts
     * — POST /v1/transfers/wallet moves funds from SubPilot's parent Nomba
     * account into a merchant's Nomba sub-account (receiverAccountId),
     * which is the natural payout path since merchant onboarding already
     * assumes a Nomba sub-account for checkout split payments (see
     * CheckoutOrderRequest's splitRequest usage elsewhere in this class).
     * Unlike /v2/transfers/bank (external bank transfer, needs
     * accountNumber+bankCode — a separate onboarding flow SubPilot doesn't
     * collect today), this fits the existing merchant model with no new
     * onboarding fields beyond nombaPayoutAccountId itself.
     *
     * Note: per the docs example, "amount" here is a plain JSON number in
     * major currency units (e.g. 3500 for ₦3,500), NOT the decimal-string
     * convention used by /checkout/order and /refund — deliberately
     * converted differently below (toMajorUnitsNumber, not
     * toMajorUnitsString) to match.
     */
    /**
     * Confirmed against developer.nomba.com/docs/products/transfers/transfer-to-banks
     * — POST /v2/transfers/bank (note the /v2 — different from every other
     * call in this class, which are all /v1). Request shape and response
     * both confirmed directly from Nomba's own curl example, not inferred.
     */
//    @Override
//    public TransferResponse initiateBankTransfer(BankTransferRequest request) {
//        Map<String, Object> body = new LinkedHashMap<>();
//        body.put("amount", toMajorUnitsNumber(request.amountKobo()));
//        body.put("accountNumber", request.accountNumber());
//        body.put("accountName", request.accountName());
//        body.put("bankCode", request.bankCode());
//        body.put("merchantTxRef", request.idempotencyKey());
//        body.put("senderName", "SubPilot");
//        body.put("narration", request.narration() != null ? request.narration() : "SubPilot payout");
//
//        try {
//            JsonNode response = apiClient.post("/v2/transfers/bank", body);
//            log.info("Transfer response:\n{}", response.toPrettyString());
//
//            if (!apiClient.isSuccessEnvelope(response)) {
//                String description = response != null ? response.path("description").asText("transfer_rejected") : "transfer_rejected";
//                return new TransferResponse(false, null, "FAILED", description);
//            }
//
//            String status = response.path("data").path("status").asText("");
//            String reference = response.path("data").path("id").asText(request.idempotencyKey());
//
//            if ("SUCCESS".equalsIgnoreCase(status)) {
//                return new TransferResponse(true, reference, status, null);
//            }
//            // PENDING/NEW/PENDING_BILLING/REFUND all fall through here —
//            // DisbursementService branches on TransferResponse.isPending()
//            // vs isRefunded() vs plain failure to decide what to do next.
//            return new TransferResponse(false, reference, status,
//                    "Transfer not confirmed successful (status=" + status + ")");
//
//        } catch (NombaApiException e) {
//            log.error("Bank transfer failed for merchantTxRef={}: {}", request.idempotencyKey(), e.getMessage());
//            return new TransferResponse(false, null, "FAILED", "Could not reach Nomba: " + e.getMessage());
//        }
//    }
    @Override
    public TransferResponse initiateBankTransfer(BankTransferRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toMajorUnitsNumber(request.amountKobo()));
        body.put("accountNumber", request.accountNumber());
        body.put("accountName", request.accountName());
        body.put("bankCode", request.bankCode());
        body.put("merchantTxRef", request.idempotencyKey());
        body.put("senderName", "SubPilot");
        body.put("narration", request.narration() != null ? request.narration() : "SubPilot payout");

        try {
            JsonNode response = apiClient.post("/v2/transfers/bank", body);

            log.info("Nomba bank transfer response:\n{}", response.toPrettyString());
            JsonNode data = response.path("data");
            String status = data.path("status").asText("");
            String reference = data.path("id").asText(request.idempotencyKey());
            String description = response.path("description").asText(null);

            return switch (status.toUpperCase(Locale.ROOT)) {
                case "SUCCESS" -> new TransferResponse(true, reference, status, null);
                case "PENDING", "PENDING_BILLING", "NEW" -> new TransferResponse(false, reference, status, null);
                case "REFUND" -> new TransferResponse(false, reference, status, description != null ? description
                                : "Transfer was refunded by Nomba"
                );
                default -> new TransferResponse(false, reference, status, description != null ? description
                                : "Transfer failed"
                );
            };
        } catch (NombaApiException e) {
            log.error("Bank transfer failed for merchantTxRef={}: {}",
                    request.idempotencyKey(), e.getMessage());

            return new TransferResponse(
                    false,
                    null,
                    "FAILED",
                    "Could not reach Nomba: " + e.getMessage()
            );
        }
    }

    /**
     * Confirmed against Nomba's transfer-to-banks docs: for a parent-account
     * transfer, requery via GET /v1/transactions/accounts/single?transactionRef=<merchantTxRef>
     * — same endpoint as checkout verification, different query param.
     */
    @Override
    public TransferResponse verifyTransfer(String merchantTxRef) {
        try {
            JsonNode response = apiClient.get("/v1/transactions/accounts/single?transactionRef=" + merchantTxRef);

            if (!apiClient.isSuccessEnvelope(response) || response.path("data").isNull()) {
                return new TransferResponse(false, merchantTxRef, "NOT_FOUND", "No transaction found for this reference yet");
            }

            JsonNode data = response.path("data");
            String status = data.path("status").asText("UNKNOWN");
            String reference = data.path("id").asText(merchantTxRef);

            if ("SUCCESS".equalsIgnoreCase(status)) {
                return new TransferResponse(true, reference, status, null);
            }
            return new TransferResponse(false, reference, status, "Transfer status=" + status);

        } catch (NombaApiException e) {
            log.error("Failed to verify transfer merchantTxRef={}: {}", merchantTxRef, e.getMessage());
            return new TransferResponse(false, merchantTxRef, "VERIFICATION_FAILED", e.getMessage());
        }
    }

    /**
     * UNVERIFIED against Nomba's own docs page for this specific endpoint
     * — see the interface javadoc on deleteTokenizedCard for the full
     * caveat. Built against the most consistent pattern from every other
     * endpoint in this family.
     */
    @Override
    public DeleteTokenResponse deleteTokenizedCard(String tokenKey) {
        Map<String, Object> body = Map.of("tokenKey", tokenKey);
        try {
            JsonNode response = apiClient.delete("/v1/checkout/tokenized-card-data", body);

            if (!apiClient.isSuccessEnvelope(response)) {
                String description = response != null ? response.path("description").asText("delete_rejected") : "delete_rejected";
                return new DeleteTokenResponse(false, description);
            }
            return new DeleteTokenResponse(true, null);

        } catch (NombaApiException e) {
            log.error("Failed to delete tokenized card tokenKey={}: {}", tokenKey, e.getMessage());
            return new DeleteTokenResponse(false, e.getMessage());
        }
    }

    /** Confirmed against Nomba's docs — GET /v1/checkout/tokenized-card-data, paginated via data.nextPage. */
    @Override
    public TokenizedCardsPage listTokenizedCards(String page) {
        String path = "/v1/checkout/tokenized-card-data" + (page != null ? "?page=" + page : "");
        try {
            JsonNode response = apiClient.get(path);

            if (!apiClient.isSuccessEnvelope(response)) {
                return new TokenizedCardsPage(java.util.List.of(), null);
            }

            JsonNode data = response.path("data");
            String nextPage = data.path("nextPage").asText(null);
            java.util.List<TokenizedCard> cards = new java.util.ArrayList<>();
            for (JsonNode c : data.path("tokenizedCardDataList")) {
                cards.add(new TokenizedCard(
                        c.path("tokenKey").asText(null),
                        c.path("customerEmail").asText(null),
                        c.path("cardType").asText(null),
                        c.path("cardPan").asText(null),
                        c.path("tokenExpirationDate").asText(null)
                ));
            }
            return new TokenizedCardsPage(cards, nextPage);

        } catch (NombaApiException e) {
            log.error("Failed to list tokenized cards: {}", e.getMessage());
            return new TokenizedCardsPage(java.util.List.of(), null);
        }
    }

    /** Transfers API takes a plain major-units number (e.g. 3500), unlike checkout/refund's decimal-string convention — see initiateTransfer's javadoc. */
    private java.math.BigDecimal toMajorUnitsNumber(long minorUnits) {
        return java.math.BigDecimal.valueOf(minorUnits, 2); // kobo -> naira, 2 decimal places
    }

    @Override
    public BankLookupResponse lookupBankAccount(String accountNumber, String bankCode) {
        Map<String, Object> body = Map.of("accountNumber", accountNumber, "bankCode", bankCode);
        try {
            JsonNode response = apiClient.post("/v1/transfers/bank/lookup", body);

            if (!apiClient.isSuccessEnvelope(response)) {
                return new BankLookupResponse(false, accountNumber, null,
                        response != null ? response.path("description").asText("Account not found") : "Account not found");
            }

            String resolvedName = response.path("data").path("accountName").asText(null);
            return new BankLookupResponse(true, accountNumber, resolvedName, null);

        } catch (NombaApiException e) {
            log.error("Bank account lookup failed for accountNumber={} bankCode={}: {}", accountNumber, bankCode, e.getMessage());
            return new BankLookupResponse(false, accountNumber, null, "Could not reach Nomba: " + e.getMessage());
        }
    }

    @Override
    public List<BankInfo> listBanks() {
        try {
            JsonNode response = apiClient.get("/v1/transfers/banks");
            if (!apiClient.isSuccessEnvelope(response)) return List.of();

            List<BankInfo> banks = new java.util.ArrayList<>();
            for (JsonNode bank : response.path("data")) {
                banks.add(new BankInfo(bank.path("name").asText(""), bank.path("code").asText("")));
            }
            return banks;
        } catch (NombaApiException e) {
            log.error("Failed to fetch bank list: {}", e.getMessage());
            return List.of();
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
            JsonNode response = apiClient.post("/v1/refund", body);

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