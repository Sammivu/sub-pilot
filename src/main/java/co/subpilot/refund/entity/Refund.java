package co.subpilot.refund.entity;

import co.subpilot.common.entity.BaseEntity;
import co.subpilot.refund.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "refunds")
public class Refund extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "payment_attempt_id")
    private String paymentAttemptId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false)
    @Builder.Default
    private String currency = "NGN";

    /** See PlatformFeeService.calculateFeeReversal — the merchant's fee is refunded proportionally, not the full original fee. */
    @Column(name = "platform_fee_refunded", nullable = false)
    @Builder.Default
    private long platformFeeRefunded = 0;

    @Column(name = "reason")
    private String reason;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = RefundStatus.PENDING;

    /**
     * Maps onto the EXISTING provider_reference column from V4 — deliberately
     * not renamed to avoid a redundant duplicate column; this class's Java
     * field name stays nombaReference for consistency with the rest of the
     * codebase's naming (Invoice.nombaReference, Subscription.nombaCardTokenRef).
     */
    @Column(name = "provider_reference")
    private String nombaReference;

    /**
     * Maps onto V4's existing idempotency_key column (NOT NULL UNIQUE).
     * Must be set to a real value before the FIRST save — see
     * RefundService, which generates the entity's id upfront specifically
     * so it can also be used here, rather than relying on BaseEntity's
     * @PrePersist auto-generation (which would run too late to satisfy the
     * NOT NULL constraint on this column).
     */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "requested_by_user_id")
    private String requestedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public boolean isTerminal() {
        return RefundStatus.SUCCEEDED.equals(status) || RefundStatus.FAILED.equals(status);
    }
}