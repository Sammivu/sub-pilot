package co.subpilot.dunning.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "dunning_steps")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DunningStep extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private String campaignId;

    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @Column(name = "day_offset", nullable = false)
    private Integer dayOffset; // days after initial failure

    @Column(name = "action", nullable = false)
    private String action; // retry_charge | send_email | both

    @Column(name = "email_template")
    private String emailTemplate; // payment_failed | final_warning | service_suspended
}