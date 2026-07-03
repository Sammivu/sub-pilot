package co.subpilot.disbursement.entity;

import co.subpilot.common.entity.BaseEntity;
import co.subpilot.disbursement.DisbursementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One payout batch to a merchant, moving accumulated netAmount out of
 * SubPilot's pooled central Nomba wallet into the merchant's own
 * nombaPayoutAccountId — see DisbursementService for the batching logic
 * and NombaReconciliationJob-style reasoning on why period_start/period_end
 * exist (idempotent cursor so re-triggering never double-pays).
 */
@Getter
@Setter
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "disbursements")
public class Disbursement extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false)
    @Builder.Default
    private String currency = "NGN";

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = DisbursementStatus.PENDING;

    /** Null on a merchant's first-ever payout — covers "everything up to periodEnd" in that case. */
    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "invoice_count", nullable = false)
    @Builder.Default
    private int invoiceCount = 0;

    @Column(name = "nomba_transfer_reference")
    private String nombaTransferReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "triggered_by_user_id")
    private String triggeredByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public boolean isTerminal() {
        return DisbursementStatus.SUCCEEDED.equals(status) || DisbursementStatus.FAILED.equals(status);
    }
}