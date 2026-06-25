package co.subpilot.event.repository;

import co.subpilot.event.entity.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, String> {
    Page<Event> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);
    Page<Event> findByMerchantIdAndTypeOrderByCreatedAtDesc(String merchantId, String type, Pageable pageable);
    Page<Event> findBySubscriptionIdOrderByCreatedAtDesc(String subscriptionId, Pageable pageable);
    Page<Event> findByMerchantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String merchantId, String resourceType, String resourceId, Pageable pageable);


    Page<Event> findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(
            String merchantId, String subscriptionId, Pageable pageable);

}