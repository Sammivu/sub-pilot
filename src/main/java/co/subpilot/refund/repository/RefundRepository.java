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
    @Query(value = """
        SELECT * FROM refunds r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:merchantId IS NULL OR r.merchant_id = :merchantId)
          AND (:resolvedByAdminId IS NULL OR r.resolved_by_admin_id = :resolvedByAdminId)
          AND (CAST(:fromDate AS timestamptz) IS NULL OR r.created_at >= CAST(:fromDate AS timestamptz))
          AND (CAST(:toDate AS timestamptz) IS NULL OR r.created_at <= CAST(:toDate AS timestamptz))
        """,
            countQuery = """
        SELECT COUNT(*) FROM refunds r
        WHERE (:status IS NULL OR r.status = :status)
          AND (:merchantId IS NULL OR r.merchant_id = :merchantId)
          AND (:resolvedByAdminId IS NULL OR r.resolved_by_admin_id = :resolvedByAdminId)
          AND (CAST(:fromDate AS timestamptz) IS NULL OR r.created_at >= CAST(:fromDate AS timestamptz))
          AND (CAST(:toDate AS timestamptz) IS NULL OR r.created_at <= CAST(:toDate AS timestamptz))
        """,
            nativeQuery = true)
    Page<Refund> findAllWithFilters(
            @Param("status") String status,
            @Param("merchantId") String merchantId,
            @Param("resolvedByAdminId") String resolvedByAdminId,
            @Param("fromDate") String fromDate,
            @Param("toDate") String toDate,
            Pageable pageable
    );
}