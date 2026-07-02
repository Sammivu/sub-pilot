package co.subpilot.subscription.repository;

import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {

    Optional<Subscription> findByIdAndMerchantId(String id, String merchantId);

    Optional<Subscription> findBySubscriptionToken(String token);

    Page<Subscription> findByMerchantId(String merchantId, Pageable pageable);

    Page<Subscription> findByMerchantIdAndStatus(String merchantId, SubscriptionStatus status, Pageable pageable);

    Page<Subscription> findByMerchantIdAndPlanId(String merchantId, String planId, Pageable pageable);

    Page<Subscription> findByCustomerIdAndMerchantId(String customerId, String merchantId, Pageable pageable);

    List<Subscription> findByCustomerId(String customerId);

    /**
     * Billing engine entry point: all active subscriptions due for renewal right now.
     */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'active' AND s.nextBillingDate <= :now")
    List<Subscription> findDueForRenewal(@Param("now") Instant now);

    /**
     * TSQ reconciliation target: subscriptions whose initial (or card-update)
     * Nomba checkout has never been confirmed by a webhook. A subscription
     * with no card token yet, older than the given cutoff, and not in a
     * terminal state, means the redirect happened but we never heard back —
     * either the webhook was lost/delayed or the subscriber never finished
     * paying. TSQ resolves the "lost webhook" case; a still-pending Nomba
     * status just means "keep waiting", handled by the caller.
     *
     * notOlderThan bounds the OTHER end: without it, a checkout that keeps
     * confirming SUCCESS-but-no-token-available (see
     * NombaReconciliationService's "needs manual follow-up" WARN) gets
     * requeried forever, every single sweep, indefinitely. Nomba's own
     * sandbox data retention is 48 hours ("Orders and their data are stored
     * for 48 hours before expiring" — see sandbox-testing docs), which is
     * also a reasonable real-world cutoff: if nobody's resolved this
     * manually within that window, hammering Nomba every 5 minutes stops
     * helping and just adds noise/log spam.
     */
    @Query("SELECT s FROM Subscription s WHERE s.nombaCardTokenRef IS NULL " +
            "AND s.status IN ('trialing', 'active') AND s.createdAt <= :cutoff AND s.createdAt >= :notOlderThan")
    List<Subscription> findPendingCheckoutConfirmation(@Param("cutoff") Instant cutoff, @Param("notOlderThan") Instant notOlderThan);

    @Query("SELECT s FROM Subscription s WHERE s.nombaCardTokenRef IS NULL " +
            "AND s.status IN ('trialing', 'active') AND s.createdAt < :notOlderThan")
    List<Subscription> findAgedOutPendingCheckouts(@Param("notOlderThan") Instant notOlderThan);

    @Query("SELECT s FROM Subscription s WHERE s.pendingCardUpdateAt IS NOT NULL " +
            "AND s.pendingCardUpdateAt <= :cutoff AND s.pendingCardUpdateAt >= :notOlderThan")
    List<Subscription> findPendingCardUpdateConfirmation(@Param("cutoff") Instant cutoff, @Param("notOlderThan") Instant notOlderThan);

    long countByMerchantIdAndStatus(String merchantId, SubscriptionStatus status);

    // Churn: cancelled in the last N days (for the rolling churn-rate metric)
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.merchantId = :merchantId " +
            "AND s.status = 'cancelled' AND s.cancelledAt >= :since")
    long countCancelledSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    // New subscriber acquisitions in a date range (PRD §6.8: "Acquisitions in selected date range").
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.merchantId = :merchantId AND s.createdAt >= :since")
    long countCreatedSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    /**
     * For the "Subscription growth" chart — every subscription created in a
     * window, ordered chronologically, so the caller can bucket them into
     * daily/weekly/monthly points without needing a separate query per bucket.
     */
    @Query("SELECT s FROM Subscription s WHERE s.merchantId = :merchantId AND s.createdAt >= :since ORDER BY s.createdAt ASC")
    List<Subscription> findCreatedSinceOrderByCreatedAtAsc(@Param("merchantId") String merchantId, @Param("since") Instant since);
}