package co.subpilot.payment.repository;

import co.subpilot.payment.entity.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String> {
    Optional<PaymentAttempt> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentAttempt> findByNombaReference(String nombaReference);
    List<PaymentAttempt> findByInvoiceIdOrderByAttemptedAtDesc(String invoiceId);
    long countByMerchantIdAndStatus(String merchantId, String status);
    long countByMerchantId(String merchantId);

    List<PaymentAttempt> findBySubscriptionIdOrderByAttemptedAtDesc(String subscriptionId);
}