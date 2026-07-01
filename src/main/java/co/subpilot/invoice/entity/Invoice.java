package co.subpilot.invoice.entity;

import co.subpilot.common.entity.BaseEntity;
import co.subpilot.invoice.InvoiceStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
@Getter
@Setter
@Entity
@Builder @AllArgsConstructor @NoArgsConstructor
@Table(name = "invoices")
public class Invoice extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "subscription_id", nullable = false)
    private String subscriptionId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "currency", nullable = false)
    private String currency = "NGN";

    @Column(name = "status", nullable = false)
    private String status = InvoiceStatus.PENDING;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "proration_note")
    private String prorationNote;


    /**
     * SubPilot's cut of this invoice, captured at the moment the charge
     * succeeded (minor units). Zero until the invoice is paid.
     */
    @Column(name = "platform_fee_amount", nullable = false)
    private long platformFeeAmount = 0;

    /**
     * Merchant payout for this invoice = amount - platformFeeAmount.
     */
    @Column(name = "net_amount", nullable = false)
    private long netAmount = 0;

    @Column(name = "fee_bps_applied")
    private Integer feeBpsApplied;

    @Column(name = "fee_fixed_applied")
    private Long feeFixedApplied;

    @Column(name = "nomba_reference")
    private String nombaReference;

    public boolean isPaid() {
        return InvoiceStatus.PAID.equals(status);
    }

    public boolean isFailed() {
        return InvoiceStatus.FAILED.equals(status);
    }

}