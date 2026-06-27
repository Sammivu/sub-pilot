package co.subpilot.nomba.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionData(
        String transactionId,
        String merchantTxRef,
        Double transactionAmount,
        String status
) {
}