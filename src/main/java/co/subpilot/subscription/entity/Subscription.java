package co.subpilot.subscription.entity;

import co.subpilot.common.entity.BaseEntity;
import co.subpilot.subscription.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "subscriptions")
public class Subscription extends BaseEntity {

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.trialing;

    @Column(name = "current_period_start", nullable = false)
    private Instant currentPeriodStart;

    @Column(name = "current_period_end", nullable = false)
    private Instant currentPeriodEnd;

    @Column(name = "next_billing_date")
    private Instant nextBillingDate;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "nomba_customer_ref")
    private String nombaCustomerRef;

    @Column(name = "nomba_card_token_ref")
    private String nombaCardTokenRef;

    /** TSQ tracking (V13) — set when a card-update checkout is initiated, cleared on resolution either way. */
    @Column(name = "pending_card_update_at")
    private Instant pendingCardUpdateAt;

    @Column(name = "subscription_token", nullable = false, unique = true)
    private String subscriptionToken;

    @PrePersist
    private void ensureToken() {
        if (subscriptionToken == null) {
            subscriptionToken = UUID.randomUUID().toString();
        }
    }

    public boolean isTerminal() {
        return status == SubscriptionStatus.cancelled || status == SubscriptionStatus.expired;
    }
    public boolean isActive() {
        return status == SubscriptionStatus.active;
    }

    public boolean isPaused() {
        return status == SubscriptionStatus.paused;
    }

    public boolean isPastDue() {
        return status == SubscriptionStatus.past_due;
    }

    public boolean isTrialing() {
        return status == SubscriptionStatus.trialing;
    }
}