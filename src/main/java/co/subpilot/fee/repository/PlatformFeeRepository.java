package co.subpilot.fee.repository;

import co.subpilot.fee.entity.PlatformFee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PlatformFeeRepository extends JpaRepository<PlatformFee, String> {

    Page<PlatformFee> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Optional<PlatformFee> findByInvoiceId(String invoiceId);
    Optional<PlatformFee> findByMerchantIdAndInvoiceId(String merchantId, String invoiceId);

    @Query("SELECT COALESCE(SUM(p.feeAmount), 0) FROM PlatformFee p WHERE p.merchantId = :merchantId " +
            "AND p.createdAt >= :since")
    long sumFeesByMerchantSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(p.feeAmount), 0) FROM PlatformFee p WHERE p.createdAt >= :since")
    long sumAllFeesSince(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(p.grossAmount), 0) FROM PlatformFee p WHERE p.merchantId = :merchantId " +
            "AND p.createdAt >= :since")
    long sumGrossByMerchantSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    /** Disbursement batching — sum of merchant payout owed for fees ledgered strictly after `since` (exclusive), so re-running never double-counts the boundary row. */
    @Query("SELECT COALESCE(SUM(p.netAmount), 0) FROM PlatformFee p WHERE p.merchantId = :merchantId " +
            "AND p.createdAt > :since AND p.createdAt <= :until")
    long sumNetByMerchantBetween(@Param("merchantId") String merchantId, @Param("since") Instant since, @Param("until") Instant until);

    @Query("SELECT COUNT(p) FROM PlatformFee p WHERE p.merchantId = :merchantId " +
            "AND p.createdAt > :since AND p.createdAt <= :until")
    long countByMerchantBetween(@Param("merchantId") String merchantId, @Param("since") Instant since, @Param("until") Instant until);
}