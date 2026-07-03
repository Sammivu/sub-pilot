package co.subpilot.disbursement.repository;

import co.subpilot.disbursement.entity.Disbursement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, String> {

    Page<Disbursement> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Optional<Disbursement> findByIdAndMerchantId(String id, String merchantId);

    /**
     * The idempotent cursor — the most recent SUCCEEDED disbursement's
     * periodEnd is where the next batch picks up from. A pending/failed
     * disbursement is deliberately excluded from this lookup: if the last
     * attempt failed, the next trigger should re-cover the same period
     * (including whatever failed), not silently skip past it.
     */
    /**
     * Guards against the exact duplicate-payout risk Nomba's docs warn
     * about — DisbursementService.trigger() must refuse to create a second
     * Disbursement (and therefore a second merchantTxRef) while one is
     * still pending, rather than let an operator retry into a double
     * transfer.
     */
    java.util.Optional<Disbursement> findByMerchantIdAndStatus(String merchantId, String status);

    /** For DisbursementReconciliationJob's sweep across all merchants. */
    java.util.List<Disbursement> findByStatus(String status);

    @Query("SELECT d FROM Disbursement d WHERE d.merchantId = :merchantId AND d.status = 'succeeded' " +
            "ORDER BY d.periodEnd DESC LIMIT 1")
    Optional<Disbursement> findLastSuccessful(@Param("merchantId") String merchantId);
}