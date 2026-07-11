package co.subpilot.refund.repository;

import co.subpilot.refund.entity.Refund;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, String> {
    List<Refund> findByInvoiceIdOrderByCreatedAtDesc(String invoiceId);
    Optional<Refund> findByIdAndMerchantId(String id, String merchantId);

    // TSQ-style reconciliation, mirroring PaymentAttemptRepository's stuck-attempt sweep.
    List<Refund> findByStatus(String status);
    @Query("""
    SELECT r FROM Refund r
    WHERE (:status IS NULL OR r.status = :status)
      AND (:merchantId IS NULL OR r.merchantId = :merchantId)
      AND (:resolvedByAdminId IS NULL OR r.resolvedByAdminId = :resolvedByAdminId)
      AND (:fromDate IS NULL OR r.createdAt >= :fromDate)
      AND (:toDate IS NULL OR r.createdAt <= :toDate)
    """)
    Page<Refund> findAllWithFilters(
            @Param("status") String status,
            @Param("merchantId") String merchantId,
            @Param("resolvedByAdminId") String resolvedByAdminId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            Pageable pageable
    );
}