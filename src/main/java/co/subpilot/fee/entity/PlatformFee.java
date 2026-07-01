package co.subpilot.fee.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable ledger of SubPilot's platform fee on every successful charge.
 *
 * One row is written per successful PaymentAttempt. This is SubPilot's own
 * revenue record — never updated after creation. Refunds write a separate
 * adjustment via the Refund entity's platformFeeRefunded field rather than
 * mutating this row, to preserve a clean audit trail.
 */
@Getter
@Setter
@Entity
@Table(name = "platform_fees")
public class PlatformFee {

    @Id
    @Column(name = "id", length = 26, nullable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "payment_attempt_id")
    private String paymentAttemptId;

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount; // total amount charged to the customer (minor units)

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount; // SubPilot's cut (minor units)

    @Column(name = "net_amount", nullable = false)
    private long netAmount; // merchant payout = gross - fee

    @Column(name = "currency", nullable = false)
    private String currency = "NGN";

    @Column(name = "fee_bps_applied", nullable = false)
    private int feeBpsApplied;

    @Column(name = "fee_fixed_applied", nullable = false)
    private long feeFixedApplied;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}