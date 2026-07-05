package co.subpilot.internal.audit.repository;

import co.subpilot.internal.audit.entity.InternalAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InternalAuditLogRepository extends JpaRepository<InternalAuditLog, String> {

    /**
     * All filters optional (null = don't filter on that field) — covers
     * "filterable by merchant ID, actor admin ID, action type, date range"
     * without four separate near-duplicate query methods.
     */
//    @Query("""
//            SELECT a
//            FROM InternalAuditLog a
//            WHERE
//            (:targetId IS NULL OR a.targetId = :targetId)
//            AND (:actorAdminId IS NULL OR a.actorAdminId = :actorAdminId)
//            AND (:actionType IS NULL OR a.actionType = :actionType)
//            AND (CAST(:from AS timestamp) IS NULL OR a.createdAt >= :from)
//            AND (CAST(:to AS timestamp) IS NULL OR a.createdAt <= :to)
//            ORDER BY a.createdAt DESC
//            """)
//    Page<InternalAuditLog> search(
//            @Param("targetId") String targetId,
//            @Param("actorAdminId") String actorAdminId,
//            @Param("actionType") String actionType,
//            @Param("from") Instant from,
//            @Param("to") Instant to,
//            Pageable pageable);

    @Query("""
            SELECT a
            FROM InternalAuditLog a
            WHERE
                (:targetId IS NULL OR a.targetId = :targetId)
            AND (:actorAdminId IS NULL OR a.actorAdminId = :actorAdminId)
            AND (:actionType IS NULL OR a.actionType = :actionType)
            AND a.createdAt >= :from
            AND a.createdAt <= :to
            ORDER BY a.createdAt DESC
            """)
    Page<InternalAuditLog> search(
            @Param("targetId") String targetId,
            @Param("actorAdminId") String actorAdminId,
            @Param("actionType") String actionType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}