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
     *
     * CAST(:from/:to AS timestamp) — same class of bug as the earlier
     * LOWER(bytea) issue, different data type: when :from/:to bind as null,
     * Postgres can't infer the parameter's type from context (no LOWER() to
     * signal "this is text" the way the string searches had), and fails with
     * "could not determine data type of parameter $N". Explicit CAST pins the
     * type regardless of nullness, same fix, same root cause.
     */
    @Query("SELECT a FROM InternalAuditLog a WHERE " +
            "(:targetId IS NULL OR a.targetId = :targetId) AND " +
            "(:actorAdminId IS NULL OR a.actorAdminId = :actorAdminId) AND " +
            "(:actionType IS NULL OR a.actionType = :actionType) AND " +
            "a.createdAt >= :from AND " +
            "a.createdAt <= :to " +
            "ORDER BY a.createdAt DESC")
    Page<InternalAuditLog> search(
            @Param("targetId") String targetId,
            @Param("actorAdminId") String actorAdminId,
            @Param("actionType") String actionType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}