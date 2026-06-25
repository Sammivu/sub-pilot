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
    @Query("SELECT s FROM Subscription s WHERE s.status = co.subpilot.subscription.enums.SubscriptionStatus.active AND s.nextBillingDate <= :now")
    List<Subscription> findDueForRenewal(@Param("now") Instant now);

    long countByMerchantIdAndStatus(String merchantId, SubscriptionStatus status);

    // Churn: cancelled in the last N days (for the rolling churn-rate metric)
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.merchantId = :merchantId " +
            "AND s.status = co.subpilot.subscription.enums.SubscriptionStatus.cancelled AND s.cancelledAt >= :since")
    long countCancelledSince(@Param("merchantId") String merchantId, @Param("since") Instant since);
}