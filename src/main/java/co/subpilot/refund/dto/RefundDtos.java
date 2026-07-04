package co.subpilot.refund.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class RefundDtos {

    /** amount is optional — omit for a full refund of the invoice's paid amount minus any prior refunds. */
    public record CreateRefundRequest(
            @Positive Long amount,
            @Size(max = 500) String reason
    ) {}

    public record RefundResponse(
            String id,
            String invoiceId,
            long amount,
            String currency,
            long platformFeeRefunded,
            String status,
            String reason,
            String nombaReference,
            String failureReason,
            String createdAt,
            String resolvedAt
    ) {
        public static RefundResponse from(co.subpilot.refund.entity.Refund r) {
            return new RefundResponse(
                    r.getId(), r.getInvoiceId(), r.getAmount(), r.getCurrency(),
                    r.getPlatformFeeRefunded(), r.getStatus(), r.getReason(),
                    r.getNombaReference(), r.getFailureReason(),
                    r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                    r.getResolvedAt() != null ? r.getResolvedAt().toString() : null
            );
        }
    }
}