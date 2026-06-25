package co.subpilot.invoice.repository;

import co.subpilot.invoice.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
    Long sumPaidSince(String merchantId, Instant since);

    // Count failed invoices
    long countByMerchantIdAndStatus(String merchantId, String status);
    
    boolean existsBySubscriptionIdAndPeriodStart(String subscriptionId, Instant periodStart);

}