package co.subpilot.nomba.dto;

public record TokenizedCardData(
        String tokenKey,
        String cardType,
        String tokenExpiryYear,
        String tokenExpiryMonth,
        String cardPan
) {
}