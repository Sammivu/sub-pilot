package co.subpilot.nomba;

import co.subpilot.nomba.dto.VerificationResponse;

import java.util.List;

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
     * Initiate a payout to a merchant's Nomba account/wallet via the
     * Nomba Transfers API — distinct from initiateRefund, which reverses a
     * charge at the issuer level. This moves already-settled platform
     * balance out to the merchant. Used by DisbursementService.
     */
    /**
     * Initiate a payout to a merchant's external bank account via
     * POST /v2/transfers/bank — NOT the Nomba-to-Nomba wallet transfer
     * (that requires the recipient to already have a Nomba accountId,
     * which arbitrary merchants don't have — see Merchant.java's javadoc
     * on the payout fields). Used by DisbursementService.
     */
    TransferResponse initiateBankTransfer(BankTransferRequest request);

    /**
     * Confirmed against developer.nomba.com/docs/products/transfers/bank-account-lookup
     * — POST /v1/transfers/bank/lookup. Resolves accountNumber+bankCode to
     * the real account holder's name at the bank, BEFORE ever saving a
     * merchant's payout details or sending real money. Never trust a
     * merchant-typed accountName — always use what this returns.
     */
    BankLookupResponse lookupBankAccount(String accountNumber, String bankCode);

    /** GET /v1/transfers/banks — bank name -> bankCode list, for a payout-settings bank picker. */
    List<BankInfo> listBanks();

    /**
     * Requeries a transfer's final status — confirmed against Nomba's own
     * duplicate-payout prevention guidance (developer.nomba.com/docs/products/transfers/transfer-to-banks):
     * "Verify Transaction Status — use the Requery endpoint... Do NOT
     * generate a new reference for the same transaction if it is still
     * pending." This is GET /v1/transactions/accounts/single?transactionRef=<merchantTxRef>.
     */
    TransferResponse verifyTransfer(String merchantTxRef);

    /**
     * Deletes a saved tokenized card from Nomba's side — called on
     * subscription cancellation so a cancelled subscription doesn't leave
     * a live, chargeable card credential sitting on Nomba's account
     * indefinitely.
     *
     * UNVERIFIED against Nomba's own docs — same caveat as
     * initiateRefund elsewhere in this file. Nomba's API reference
     * confirms this endpoint exists ("DEL Delete tokenized card data",
     * listed in their nav alongside List/Update tokenized card data), but
     * search could not surface its own page's exact request shape (path
     * params vs query vs body). Implemented here against the most
     * consistent pattern from every other endpoint in this same
     * online-checkout family — DELETE /v1/checkout/tokenized-card-data
     * with accountId header and tokenKey as a query param, mirroring how
     * List tokenized cards (GET, same base path) and Charge tokenized
     * cards (POST, tokenKey in body) both identify a card. Confirm
     * against Nomba's actual docs page before depending on this in a real
     * cancellation flow at scale.
     */
    DeleteTokenResponse deleteTokenizedCard(String tokenKey);

    /**
     * Lists tokenized cards on the account — confirmed against Nomba's
     * docs (GET /v1/checkout/tokenized-card-data, paginated via
     * data.nextPage). Not filterable by customer server-side; callers
     * filter the returned list by customerEmail themselves.
     */
    TokenizedCardsPage listTokenizedCards(String page);

    VirtualAccountResponse createVirtualAccount(VirtualAccountRequest request);

    record DeleteTokenResponse(boolean success, String failureReason) {}

    record TokenizedCard(String tokenKey, String customerEmail, String cardType, String cardPan, String tokenExpirationDate) {}

    record TokenizedCardsPage(List<TokenizedCard> cards, String nextPage) {}

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
            String nombaCustomerId,
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

    record BankTransferRequest(
            String accountNumber,
            String accountName,
            String bankCode,
            long amountKobo,
            String currency,
            String idempotencyKey,  // merchantTxRef — one per Disbursement, never reused for a retry (see DisbursementService)
            String narration
    ) {}

    /**
     * status carries Nomba's raw value (SUCCESS, PENDING, PENDING_BILLING,
     * NEW, REFUND, FAILED, etc.) — success alone collapses too much. Per
     * Nomba's own duplicate-payout guidance:
     *   - PENDING/NEW/PENDING_BILLING: NOT failure — do not retry with a
     *     new reference, requery instead (isPending()).
     *   - REFUND: a genuinely terminal, safe-to-retry outcome — the
     *     transfer couldn't be delivered and Nomba auto-reversed it; their
     *     docs explicitly say a NEW merchantTxRef is safe here, unlike
     *     PENDING (isRefunded()).
     */
    record TransferResponse(
            boolean success,
            String reference,
            String status,
            String failureReason
    ) {
        public boolean isPending() {
            return !success && (
                    "PENDING".equalsIgnoreCase(status) ||
                            "PENDING_BILLING".equalsIgnoreCase(status) ||
                            "NEW".equalsIgnoreCase(status)
            );
        }

        public boolean isRefunded() {
            return !success && "REFUND".equalsIgnoreCase(status);
        }
    }

    record BankLookupResponse(
            boolean found,
            String accountNumber,
            String accountName,  // the REAL name at the bank — always use this, never a merchant-typed value
            String failureReason
    ) {}

    record BankInfo(String name, String code) {}

    record VirtualAccountRequest(
            String accountReference,   // becomes the narration — use "transfer_{subscriptionId}"
            String accountName,        // customer's full name
            long   expectedAmountKobo, // Nomba can enforce exact-amount matching
            String currency,
            String expiryDate
    ) {}

    record VirtualAccountResponse(
            boolean success,
            String  accountNumber,
            String  bankName,
            String  bankCode,
            String  accountReference,
            String  errorMessage
    ) {}
}