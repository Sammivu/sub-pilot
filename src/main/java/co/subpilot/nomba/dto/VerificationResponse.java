package co.subpilot.nomba.dto;

/**
 * transactionId/cardToken/customerId are populated only when status is a
 * successful, card-tokenizing transaction (new-subscription or card-update
 * checkout) — they mirror the same fields the inbound payment_success
 * webhook carries, since TSQ reconciliation needs to be able to finish the
 * exact same activation flow the webhook handler would have run.
 * They will be null for renewal/dunning charge verifications, which don't
 * need them.
 */
public record VerificationResponse(
        boolean success,
        String reference,
        String status,
        String transactionId,
        String cardToken,
        String customerId
) {
    public VerificationResponse(boolean success, String reference, String status) {
        this(success, reference, status, null, null, null);
    }
}