package co.subpilot.invoice.repository;

import co.subpilot.invoice.entity.InvoiceSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, String> {

    /**
     * Row-level lock to safely increment the sequence under concurrent billing runs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InvoiceSequence s WHERE s.merchantId = :merchantId")
    Optional<InvoiceSequence> findForUpdate(@Param("merchantId") String merchantId);
}