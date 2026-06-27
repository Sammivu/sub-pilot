package co.subpilot.nomba.dto;

public record CheckoutOrderRequest(
        Order order,
        boolean tokenizeCard
) {
}