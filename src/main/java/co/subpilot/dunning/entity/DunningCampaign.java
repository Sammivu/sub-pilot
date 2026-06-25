package co.subpilot.dunning.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "dunning_campaigns")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DunningCampaign extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "name", nullable = false)
    @Builder.Default
    private String name = "Default Campaign";

    @Column(name = "grace_period_days", nullable = false)
    @Builder.Default
    private Integer gracePeriodDays = 21;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 4;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = true;

    @Column(name = "cancel_after_exhaustion", nullable = false)
    @Builder.Default
    private Boolean cancelAfterExhaustion = true;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "campaign_id")
    @OrderBy("stepNumber ASC")
    @Builder.Default
    private List<DunningStep> steps = new ArrayList<>();
}