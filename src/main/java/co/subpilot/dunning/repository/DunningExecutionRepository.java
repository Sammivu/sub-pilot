package co.subpilot.dunning.repository;

import co.subpilot.dunning.entity.DunningExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DunningExecutionRepository extends JpaRepository<DunningExecution, String> {
    List<DunningExecution> findByStatusOrderByStartedAtAsc(String status);
    Optional<DunningExecution> findBySubscriptionIdAndStatus(String subscriptionId, String status);
    Optional<DunningExecution> findByInvoiceIdAndStatus(String invoiceId, String status);

    // Dashboard stats — all-time
    @Query("SELECT COUNT(e) FROM DunningExecution e WHERE e.merchantId = :merchantId AND e.status = 'resolved'")
    long countResolvedByMerchant(@Param("merchantId") String merchantId);

    @Query("SELECT COUNT(e) FROM DunningExecution e WHERE e.merchantId = :merchantId AND e.status = 'active'")
    long countActiveByMerchant(@Param("merchantId") String merchantId);

    // Dashboard stats — windowed by when the execution STARTED (i.e. when the
    // underlying payment first failed), regardless of when/whether it later
    // resolved. This answers "of the failures that began in this window,
    // what fraction did we recover?" — the PRD §16 target metric
    // ("Dunning recovery rate >= 30% of failed payments recovered").
    @Query("SELECT COUNT(e) FROM DunningExecution e WHERE e.merchantId = :merchantId " +
            "AND e.startedAt >= :since AND e.status = 'resolved'")
    long countResolvedByMerchantSince(@Param("merchantId") String merchantId, @Param("since") Instant since);

    @Query("SELECT COUNT(e) FROM DunningExecution e WHERE e.merchantId = :merchantId AND e.startedAt >= :since")
    long countStartedByMerchantSince(@Param("merchantId") String merchantId, @Param("since") Instant since);
}