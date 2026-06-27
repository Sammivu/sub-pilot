package co.subpilot.nomba.dto;

/**
 * Maps the response from GET /v1/transactions/accounts/single?orderReference=...
 * Nomba's documented "Verify Transactions" endpoint.
 *
 * Real payload shape: { "code": "00", "description": "Success", "data": { ... } }
 * — TransactionData carries the actual status/amount/etc fields.
 */
public record VerifyTransactionResponse(
        String code,
        String description,
        TransactionData data
) {
    public boolean isSuccessEnvelope() {
        return "00".equals(code);
    }
}