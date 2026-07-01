package co.subpilot.plan.entity;

import co.subpilot.common.entity.BaseEntity;
import co.subpilot.plan.BillingInterval;
import co.subpilot.plan.PlanStatus;
import co.subpilot.plan.ProrationPolicy;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Plan extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "description")
    private String description;

    @Column(name = "amount", nullable = false)
    private long amount; // minor units (kobo)

    @Column(name = "currency", nullable = false)
    private String currency = "NGN";

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false)
    private BillingInterval billingInterval;

    @Column(name = "interval_value", nullable = false)
    private int intervalValue = 1;

    @Column(name = "interval_unit")
    private String intervalUnit; // days|weeks|months — for custom intervals

    @Column(name = "trial_days", nullable = false)
    private int trialDays = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "proration_policy", nullable = false)
    private ProrationPolicy prorationPolicy = ProrationPolicy.none;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlanStatus status = PlanStatus.draft;

    public boolean isPublished() {
        return status == PlanStatus.published;
    }
}