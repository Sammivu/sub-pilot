package co.subpilot.webhook.repository;

import co.subpilot.webhook.entity.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, String> {
    Page<WebhookDelivery> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
    Page<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(String endpointId, Pageable pageable);
    List<WebhookDelivery> findByStatusAndNextRetryAtBefore(String status, Instant now);



    Optional<WebhookDelivery> findByIdAndMerchantId(String id, String merchantId);

    @Query("SELECT d FROM WebhookDelivery d WHERE d.status = 'pending' AND d.nextRetryAt <= :now")
    List<WebhookDelivery> findDueForRetry(@Param("now") Instant now);
}