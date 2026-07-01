package co.subpilot.invoice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "invoice_sequences")
public class InvoiceSequence {

    @Id
    @Column(name = "merchant_id", length = 26)
    private String merchantId;

    @Column(name = "last_value", nullable = false)
    private long lastValue = 0;
}