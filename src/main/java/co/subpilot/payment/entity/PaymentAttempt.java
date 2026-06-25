package co.subpilot.payment.entity;

import co.subpilot.payment.PaymentAttemptStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "payment_attempts")
public class PaymentAttempt {


    @Id
    @Column(name = "id", length = 26, nullable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "subscription_id", nullable = false)
    private String subscriptionId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "nomba_reference")
    private String nombaReference;

    @Column(name = "provider", nullable = false)
    private String provider = "nomba";

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false)
    private String currency = "NGN";

    @Column(name = "status", nullable = false)
    private String status = PaymentAttemptStatus.PENDING;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public boolean isTerminal() {
        return PaymentAttemptStatus.SUCCEEDED.equals(status) || PaymentAttemptStatus.FAILED.equals(status);
    }

    public boolean isSucceeded() {
        return PaymentAttemptStatus.SUCCEEDED.equals(status);
    }

    public boolean isFailed() {
        return PaymentAttemptStatus.FAILED.equals(status);
    }

    @PrePersist
    private void ensureId() {
        if (id == null) {
            id = com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString();
        }
    }
}