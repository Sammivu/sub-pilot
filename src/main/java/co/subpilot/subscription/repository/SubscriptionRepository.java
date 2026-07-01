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