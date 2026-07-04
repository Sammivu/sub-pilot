package co.subpilot.audit.repository;

import co.subpilot.audit.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    Page<AuditLog> findByMerchantIdOrderByCreatedAtDesc(String merchantId, Pageable pageable);

    Page<AuditLog> findByMerchantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
            String merchantId, String resourceType, String resourceId, Pageable pageable);

    Page<AuditLog> findByMerchantIdAndActionOrderByCreatedAtDesc(
            String merchantId, String action, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT a FROM AuditLog a WHERE a.merchantId = :merchantId " +
            "AND (:resourceType IS NULL OR a.resourceType = :resourceType) " +
            "AND (:resourceId IS NULL OR a.resourceId = :resourceId) " +
            "AND (:action IS NULL OR a.action = :action) " +
            "AND (:q IS NULL OR LOWER(a.resourceId) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(a.actorId) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(@Param("merchantId") String merchantId,
                          @Param("resourceType") String resourceType,
                          @Param("resourceId") String resourceId,
                          @Param("action") String action,
                          @Param("q") String q,
                          Pageable pageable);
}