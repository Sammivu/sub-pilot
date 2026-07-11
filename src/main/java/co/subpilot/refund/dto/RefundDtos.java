package co.subpilot.refund.dto;

import co.subpilot.refund.entity.Refund;
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
        public static RefundResponse from(Refund r) {
            return new RefundResponse(
                    r.getId(), r.getInvoiceId(), r.getAmount(), r.getCurrency(),
                    r.getPlatformFeeRefunded(), r.getStatus(), r.getReason(),
                    r.getNombaReference(), r.getFailureReason(),
                    r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                    r.getResolvedAt() != null ? r.getResolvedAt().toString() : null
            );
        }
    }

    public record AdminRefundResponse(
            String id, String merchantId, String invoiceId,
            long amount, String currency, long platformFeeRefunded,
            String status, String reason, String nombaReference,
            String failureReason, String resolvedByAdminId,  // ← admin only
             String createdAt, String resolvedAt
    ) {
        public static AdminRefundResponse from(Refund r) {
            return new AdminRefundResponse(
                    r.getId(), r.getMerchantId(),r.getInvoiceId(), r.getAmount(),r.getCurrency(),
                    r.getPlatformFeeRefunded(),r.getStatus(), r.getReason(), r.getNombaReference(),
                    r.getFailureReason(), r.getResolvedByAdminId(),  r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                    r.getResolvedAt() != null ? r.getResolvedAt().toString() : null
            );
        }
    }
}