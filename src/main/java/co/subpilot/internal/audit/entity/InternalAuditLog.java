package co.subpilot.internal.audit.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Deliberately separate from AuditLog (merchant-facing, requires a
 * non-null merchantId and actorType user|api_key). An internal admin
 * action often has no single merchant (a platform fee default change) and
 * the actor is neither a merchant user nor an API key — reusing the
 * existing endpoint/type would mean loosening a contract other merchant
 * tooling depends on being tenant-scoped. Immutable once written, same as
 * AuditLog — no update path.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "internal_audit_logs")
public class InternalAuditLog {

    @Id
    @Column(name = "id", length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "actor_admin_id", nullable = false)
    private String actorAdminId;

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    /** merchant | platform_fee_policy (V1) */
    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    /** e.g. merchant_status_changed, platform_fee_updated, merchant_fee_override_updated, merchant_fee_override_removed */
    @Column(name = "action_type", nullable = false)
    private String actionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue; // JSON string

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue; // JSON string

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString();
        createdAt = Instant.now();
    }
}