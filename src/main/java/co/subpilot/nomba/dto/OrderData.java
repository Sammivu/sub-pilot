package co.subpilot.nomba.dto;

public record OrderData(
        String orderReference,
        String customerEmail,
        String customerId,
        String cardLast4Digits
) {
}