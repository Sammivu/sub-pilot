package co.subpilot.dunning.repository;

import co.subpilot.dunning.entity.DunningExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DunningExecutionRepository extends JpaRepository<DunningExecution, String> {
    List<DunningExecution> findByStatusOrderByStartedAtAsc(String status);
    Optional<DunningExecution> findBySubscriptionIdAndStatus(String subscriptionId, String status);
    Optional<DunningExecution> findByInvoiceIdAndStatus(String invoiceId, String status);

    // Dashboard stats
    @Query("SELECT COUNT(e) FROM DunningExecution e WHERE e.merchantId = :merchantId AND e.status = 'resolved'")
    long countResolvedByMerchant(String merchantId);

    @Query("SELECT COUNT(e) FROM DunningExecution e WHERE e.merchantId = :merchantId AND e.status = 'active'")
    long countActiveByMerchant(String merchantId);
}