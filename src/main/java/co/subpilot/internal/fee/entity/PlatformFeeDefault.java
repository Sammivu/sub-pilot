package co.subpilot.internal.fee.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-row table (id is always the literal "default") — the DB-backed
 * replacement for what used to be a fixed application.yml value
 * (subpilot.fees.default-bps / default-fixed-minor). See
 * PlatformFeePolicy.calculate for how this and the yml fallback interact.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "platform_fee_default")
public class PlatformFeeDefault {

    public static final String SINGLETON_ID = "default";

    @Id
    @Column(name = "id", length = 20, nullable = false, updatable = false)
    @Builder.Default
    private String id = SINGLETON_ID;

    @Column(name = "fee_bps", nullable = false)
    private int feeBps;

    @Column(name = "fixed_fee_minor", nullable = false)
    private long fixedFeeMinor;

    @Column(name = "updated_by_admin_id")
    private String updatedByAdminId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}