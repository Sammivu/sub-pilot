package co.subpilot.payment.repository;

import co.subpilot.payment.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String> {
    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentAttempt> findByNombaReference(String nombaReference);
    List<PaymentAttempt> findByInvoiceIdOrderByAttemptedAtDesc(String invoiceId);
    long countByMerchantIdAndStatus(String merchantId, String status);
    long countByMerchantId(String merchantId);

    List<PaymentAttempt> findBySubscriptionIdOrderByAttemptedAtDesc(String subscriptionId);

    // TSQ reconciliation — attempts stuck in "processing" past a grace cutoff.
    List<PaymentAttempt> findByStatusAndAttemptedAtBefore(String status, Instant cutoff);

    // Analytics — windowed counts for the payment success rate metric/trend
    // (PRD §6.8: "Successful charges / total attempts").
    @Query("SELECT COUNT(p) FROM PaymentAttempt p WHERE p.merchantId = :merchantId " +
            "AND p.attemptedAt >= :since AND p.status = 'succeeded'")
    long countSucceededByMerchantSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("SELECT COUNT(p) FROM PaymentAttempt p WHERE p.merchantId = :merchantId AND p.attemptedAt >= :since")
    long countAttemptedByMerchantSince(@Param("merchantId") String merchantId, @Param("since") Instant since);
}