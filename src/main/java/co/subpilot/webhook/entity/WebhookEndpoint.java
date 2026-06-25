package co.subpilot.webhook.entity;

import co.subpilot.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "subscribed_events", columnDefinition = "text[]")
    private List<String> subscribedEvents;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "signing_secret_hash", nullable = false)
    private String signingSecretHash;

    public boolean isSubscribedTo(String eventType) {
        return active && subscribedEvents != null && subscribedEvents.contains(eventType);
    }
}