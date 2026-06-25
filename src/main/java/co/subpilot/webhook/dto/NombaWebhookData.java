package co.subpilot.webhook.dto;

import co.subpilot.nomba.dto.OrderData;
import co.subpilot.nomba.dto.TokenizedCardData;
import co.subpilot.nomba.dto.TransactionData;

public record NombaWebhookData(
        TokenizedCardData tokenizedCardData,
        TransactionData transaction,
        OrderData order
) {
}