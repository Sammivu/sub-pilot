package co.subpilot.refund.repository;

import co.subpilot.refund.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, String> {
    List<Refund> findByInvoiceIdOrderByCreatedAtDesc(String invoiceId);
    Optional<Refund> findByIdAndMerchantId(String id, String merchantId);

    // TSQ-style reconciliation, mirroring PaymentAttemptRepository's stuck-attempt sweep.
    List<Refund> findByStatus(String status);
}