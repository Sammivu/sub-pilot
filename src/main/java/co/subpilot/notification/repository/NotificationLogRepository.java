package co.subpilot.notification.repository;

import co.subpilot.notification.entity.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, String> {

    Page<NotificationLog> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Page<NotificationLog> findBySubscriptionIdOrderByCreatedAtDesc(String subscriptionId, Pageable pageable);
}