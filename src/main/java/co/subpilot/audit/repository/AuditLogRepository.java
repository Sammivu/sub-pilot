package co.subpilot.audit.repository;

import co.subpilot.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    Page<AuditLog> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Page<AuditLog> findByMerchantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String merchantId, String resourceType, String resourceId, Pageable pageable);

    Page<AuditLog> findByMerchantIdAndActionOrderByCreatedAtDesc(
            String merchantId, String action, Pageable pageable);
}