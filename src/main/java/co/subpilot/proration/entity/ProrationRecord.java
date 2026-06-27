package co.subpilot.proration.entity;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Records a single proration event — a plan change mid-billing-cycle.
 *
 * One row is written every time changePlan() runs, regardless of whether the
 * net effect was a charge, a credit, or a no-op (policy = none). This keeps
 * a complete audit trail of every plan change a subscriber made and what it
 * cost or credited them, independent of the Event log (which records that
 * a change happened; this records the financial detail of it).
 *
 * Does NOT extend BaseEntity: the proration_records table (V4 migration) has
 * only an `applied_at` timestamp, not the created_at/updated_at pair every
 * other BaseEntity subclass relies on. A proration record is also a
 * point-in-time fact, never updated after creation, so a separate
 * updated_at would be meaningless here anyway.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "proration_records")
public class ProrationRecord {

    @Id
    @Column(name = "id", length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "subscription_id", nullable = false)
    private String subscriptionId;

    @Column(name = "previous_plan_id", nullable = false)
    private String previousPlanId;

    @Column(name = "new_plan_id", nullable = false)
    private String newPlanId;

    /** Credit for unused days on the old plan, in minor units (kobo). */
    @Column(name = "credit_amount", nullable = false)
    private long creditAmount;

    /** Charge for remaining days on the new plan, in minor units (kobo). */
    @Column(name = "charge_amount", nullable = false)
    private long chargeAmount;

    /**
     * The invoice this proration was settled against — populated when an
     * immediate charge or credit-on-next-invoice was applied. Null when the
     * plan's proration policy is "none" (change takes effect at next cycle
     * with no immediate financial adjustment).
     */
    @Column(name = "invoice_id")
    private String invoiceId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = UlidCreator.getMonotonicUlid().toString();
        }
        if (this.appliedAt == null) {
            this.appliedAt = Instant.now();
        }
    }

    /** Net amount actually charged today: chargeAmount - creditAmount, floored at 0. */
    public long netChargeToday() {
        return Math.max(0, chargeAmount - creditAmount);
    }

    /** Credit carried to the next invoice when the net amount is negative (downgrade). */
    public long netCreditToNextInvoice() {
        return Math.max(0, creditAmount - chargeAmount);
    }
}