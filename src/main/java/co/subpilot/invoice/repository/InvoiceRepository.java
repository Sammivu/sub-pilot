package co.subpilot.invoice.repository;

import co.subpilot.invoice.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, String> {
    Optional<Invoice> findByIdAndMerchantId(String id, String merchantId);
    Page<Invoice> findByMerchantId(String merchantId, Pageable pageable);
    Page<Invoice> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);
    Page<Invoice> findBySubscriptionId(String subscriptionId, Pageable pageable);
    List<Invoice> findBySubscriptionIdOrderByCreatedAtDesc(String subscriptionId);

    // For idempotency: find existing invoice for a billing period
    Optional<Invoice> findBySubscriptionIdAndPeriodStart(String subscriptionId, Instant periodStart);

    // Analytics: sum of paid invoices per merchant
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Invoice i " +
            "WHERE i.merchantId = :merchantId AND i.status = 'paid' AND i.paidAt >= :since")
    Long sumPaidSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    /**
     * For the "Revenue over time" chart — every paid invoice in a window,
     * ordered chronologically, so the caller buckets into daily/weekly/
     * monthly points without a separate query per bucket. Mirrors
     * SubscriptionRepository.findCreatedSinceOrderByCreatedAtAsc.
     */
    @Query("SELECT i FROM Invoice i WHERE i.merchantId = :merchantId AND i.status = 'paid' " +
            "AND i.paidAt >= :since ORDER BY i.paidAt ASC")
    List<Invoice> findPaidSinceOrderByPaidAtAsc(@Param("merchantId") String merchantId, @Param("since") Instant since);

    // Count failed invoices
    long countByMerchantIdAndStatus(String merchantId, String status);

    // Value of currently-failed invoices (PRD §6.8: "Failed Payments — count and value")
    @Query("SELECT COALESCE(SUM(i.amount), 0) FROM Invoice i WHERE i.merchantId = :merchantId AND i.status = 'failed'")
    long sumFailedAmount(@Param("merchantId") String merchantId);

    boolean existsBySubscriptionIdAndPeriodStart(String subscriptionId, Instant periodStart);

    // For proration idempotency: dedup by a caller-supplied key tagged onto
    // prorationNote, since periodStart is "now" for proration invoices and
    // therefore unusable as a stable dedup key the way it is for regular
    // billing-cycle invoices.
    Optional<Invoice> findBySubscriptionIdAndProrationNoteStartingWith(String subscriptionId, String prefix);

}