package co.subpilot.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Record of every transactional email SubPilot attempts to send.
 * Maps to PRD §6.9 — both merchant and subscriber notifications flow
 * through here, regardless of which template/event triggered them.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    @Column(name = "id", length = 26, nullable = false)
    private String id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "subscription_id")
    private String subscriptionId;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    @Column(name = "template", nullable = false)
    private String template;

    @Column(name = "status", nullable = false)
    private String status = "pending"; // pending|sent|failed

    @Column(name = "provider", nullable = false)
    private String provider = "brevo";

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void ensureDefaults() {
        if (id == null) {
            id = com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}