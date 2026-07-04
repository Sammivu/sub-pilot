package co.subpilot.event.repository;

import co.subpilot.event.entity.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, String> {
    Page<Event> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
    Page<Event> findByMerchantIdAndTypeOrderByCreatedAtDesc(String merchantId, String type, Pageable pageable);
    Page<Event> findBySubscriptionIdOrderByCreatedAtDesc(String subscriptionId, Pageable pageable);
    Page<Event> findByMerchantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String merchantId, String resourceType, String resourceId, Pageable pageable);


    Page<Event> findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(
            String merchantId, String subscriptionId, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT e FROM Event e WHERE e.merchantId = :merchantId " +
            "AND (:type IS NULL OR e.type = :type) " +
            "AND (:subscriptionId IS NULL OR e.subscriptionId = :subscriptionId) " +
            "AND (:q IS NULL OR LOWER(e.subscriptionId) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(e.resourceId) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY e.createdAt DESC")
    Page<Event> search(@Param("merchantId") String merchantId,
                       @Param("type") String type,
                       @Param("subscriptionId") String subscriptionId,
                       @Param("q") String q,
                       Pageable pageable);

}