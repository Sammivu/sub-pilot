package co.subpilot.fee.repository;

import co.subpilot.fee.entity.PlatformFee;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    // Total SubPilot revenue across all merchants in a window
    @Query("SELECT COALESCE(SUM(p.feeAmount), 0) FROM PlatformFee p " +
            "WHERE p.createdAt >= :from AND p.createdAt <= :to")
    long sumAllFeesBetween(@Param("from") Instant from, @Param("to") Instant to);

    // PlatformFeeRepository.java — add alongside sumAllFeesBetween

    @Query("SELECT COALESCE(SUM(p.grossAmount), 0) FROM PlatformFee p " +
            "WHERE p.createdAt >= :from AND p.createdAt <= :to")
    long sumAllGrossBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT COALESCE(SUM(p.netAmount), 0) FROM PlatformFee p " +
            "WHERE p.createdAt >= :from AND p.createdAt <= :to")
    long sumAllNetBetween(@Param("from") Instant from, @Param("to") Instant to);

    // Per-merchant breakdown: gross, fee, net
    @Query("""
    SELECT p.merchantId,
           COALESCE(SUM(p.grossAmount), 0),
           COALESCE(SUM(p.feeAmount), 0),
           COALESCE(SUM(p.netAmount), 0),
           COUNT(p)
    FROM PlatformFee p
    WHERE p.createdAt >= :from AND p.createdAt <= :to
    GROUP BY p.merchantId
    ORDER BY SUM(p.grossAmount) DESC
    """)
    List<Object[]> merchantRevenueBreakdown(@Param("from") Instant from, @Param("to") Instant to);

    // SubPilot revenue over time (daily buckets) for a chart
    @Query(value = """
    SELECT DATE_TRUNC('day', created_at) AS day,
           SUM(fee_amount)               AS subpilot_revenue,
           SUM(gross_amount)             AS platform_gmv
    FROM platform_fees
    WHERE created_at >= :from AND created_at <= :to
    GROUP BY 1 ORDER BY 1
    """, nativeQuery = true)
    List<Object[]> dailyRevenueSeries(@Param("from") Instant from, @Param("to") Instant to);

    @Query("""
    SELECT p.merchantId,
           COALESCE(SUM(p.grossAmount), 0)  AS gross,
           COALESCE(SUM(p.feeAmount), 0)    AS fee,
           COALESCE(SUM(p.netAmount), 0)    AS net,
           COUNT(p)                          AS txCount
    FROM PlatformFee p
    WHERE p.createdAt >= :from
      AND p.createdAt <= :to
      AND (:minGross IS NULL OR p.merchantId IN (
            SELECT p2.merchantId FROM PlatformFee p2
            WHERE p2.createdAt >= :from AND p2.createdAt <= :to
            GROUP BY p2.merchantId
            HAVING SUM(p2.grossAmount) >= :minGross
          ))
    GROUP BY p.merchantId
    """)
    List<Object[]> merchantRevenueBreakdown(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("minGross") Long minGross  // null = no filter
    );

}