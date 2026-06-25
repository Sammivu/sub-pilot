package co.subpilot.event.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only event log. NEVER updated or deleted after creation.
 * Source of truth for analytics, webhooks, and audit.
 *
 * Does not extend BaseEntity because it has no updated_at — events are immutable.
 */

@Entity
@Table(name = "events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Event  {

    @Id
    @Column(name = "id", length = 26, nullable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "subscription_id")
    private String subscriptionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload; // JSON string

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}