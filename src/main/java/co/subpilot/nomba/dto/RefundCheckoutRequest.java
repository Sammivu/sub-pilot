package co.subpilot.nomba.dto;

/**
 * Maps to POST /v1/checkout/refund — Nomba's documented card-transaction
 * refund endpoint (reverses the original charge at the issuer level,
 * distinct from a wallet transfer/payout).
 */
public record RefundCheckoutRequest(
        String orderReference,   // the original transaction's orderReference
        String amount,           // decimal string, major units — same convention as charge/checkout
        String reason
) {
}