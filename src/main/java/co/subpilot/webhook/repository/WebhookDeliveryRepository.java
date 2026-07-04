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

    /**
     * eventType isn't a column on WebhookDelivery itself — only eventId
     * (FK) is stored — so filtering by event type needs an explicit
     * non-association JOIN to Event, same pattern as the customer joins
     * elsewhere in this round of changes.
     */
    @Query("SELECT d FROM WebhookDelivery d LEFT JOIN Event e ON e.id = d.eventId WHERE d.merchantId = :merchantId " +
            "AND (:status IS NULL OR d.status = :status) " +
            "AND (:endpointId IS NULL OR d.endpointId = :endpointId) " +
            "AND (:eventType IS NULL OR e.type = :eventType) " +
            "ORDER BY d.createdAt DESC")
    Page<WebhookDelivery> search(@Param("merchantId") String merchantId,
                                 @Param("status") String status,
                                 @Param("endpointId") String endpointId,
                                 @Param("eventType") String eventType,
                                 Pageable pageable);
}