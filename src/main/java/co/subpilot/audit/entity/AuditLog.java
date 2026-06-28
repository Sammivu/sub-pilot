package co.subpilot.audit.entity;

import com.github.f4b6a3.ulid.UlidCreator;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Records a single merchant-initiated action with full before/after context
 * (PRD §6.11). Immutable once written — there is no update path.
 *
 * Distinct from the Event log: Events record that a state change happened
 * and are the backbone for webhooks/analytics. AuditLog records WHO did it
 * and exactly WHAT changed, for compliance/debugging — e.g. "which API key
 * archived this plan, and what did it look like before."
 *
 * Does not extend BaseEntity for the same reason ProrationRecord doesn't:
 * the audit_logs table has no updated_at column, and an audit row is never
 * updated after creation.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @Column(name = "id", length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    /** The user_id or api_key_id that performed the action. */
    @Column(name = "actor_id", nullable = false)
    private String actorId;

    /** "user" or "api_key" — see AuditActorType. */
    @Column(name = "actor_type", nullable = false)
    private String actorType;

    /** e.g. "plan.archived", "subscription.cancelled", "api_key.created" — see AuditAction. */
    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", columnDefinition = "jsonb")
    private String beforeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", columnDefinition = "jsonb")
    private String afterSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void prePersist() {
        if (this.id == null) {
            this.id = UlidCreator.getMonotonicUlid().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}