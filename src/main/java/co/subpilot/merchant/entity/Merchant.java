package co.subpilot.merchant.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "merchants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Merchant extends BaseEntity {

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "webhook_signing_secret", nullable = false)
    private String webhookSigningSecret;

    /**
     * Per-merchant override of SubPilot's platform take-rate, in basis points
     * (150 = 1.5%). NULL means "use the platform default"
     * (subpilot.fees.default-bps).
     */
    @Column(name = "fee_bps")
    private Integer feeBps;

    /**
     * Per-merchant override of the fixed fee charged per transaction, in
     * minor units (kobo). NULL means "use the platform default"
     * (subpilot.fees.default-fixed-minor).
     */
    @Column(name = "fee_fixed_minor")
    private Long feeFixedMinor;
}