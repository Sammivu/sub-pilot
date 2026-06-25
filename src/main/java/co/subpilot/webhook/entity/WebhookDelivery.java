package co.subpilot.webhook.entity;

import co.subpilot.webhook.WebhookDeliveryStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    @Id
    @Column(name = "id", length = 26, nullable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "endpoint_id", nullable = false)
    private String endpointId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "status", nullable = false)
    private String status = WebhookDeliveryStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}