package co.subpilot.dunning.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "dunning_executions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DunningExecution extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "subscription_id", nullable = false)
    private String subscriptionId;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @Column(name = "campaign_id", nullable = false)
    private String campaignId;

    @Column(name = "current_step", nullable = false)
    @Builder.Default
    private Integer currentStep = 0;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "active"; // active|resolved|exhausted|cancelled

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public boolean isActive()    { return "active".equals(status); }
    public boolean isResolved()  { return "resolved".equals(status); }
    public boolean isExhausted() { return "exhausted".equals(status); }
}