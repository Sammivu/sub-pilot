package co.subpilot.plan.repository;

import co.subpilot.plan.PlanStatus;
import co.subpilot.plan.entity.Plan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, String> {
    Page<Plan> findByMerchantId(String merchantId, Pageable pageable);
    Page<Plan> findByMerchantIdAndStatus(String merchantId, PlanStatus status, Pageable pageable);

    /** q (name, case-insensitive substring) and status both optional — null means "don't filter on that field". */
    @Query("SELECT p FROM Plan p WHERE p.merchantId = :merchantId " +
            "AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))) " +
            "AND (:status IS NULL OR p.status = :status)")
    Page<Plan> search(@Param("merchantId") String merchantId,
                      @Param("q") String q,
                      @Param("status") PlanStatus status,
                      Pageable pageable);
    Optional<Plan> findByIdAndMerchantId(String id, String merchantId);
    Optional<Plan> findByMerchantIdAndSlug(String merchantId, String slug);
    // For public plan page - by merchant slug + plan slug
    Optional<Plan> findBySlugAndStatus(String slug, PlanStatus status);
    boolean existsByMerchantIdAndSlug(String merchantId, String slug);

    Optional<Plan> findBySlugAndMerchantId(String slug, String merchantId);
    // For public hosted plan pages: resolve via merchant_slug + plan_slug
    @Query("""
    SELECT p
    FROM Plan p, Merchant m
    WHERE p.merchantId = m.id
      AND m.slug = :merchantSlug
      AND p.slug = :planSlug
      AND p.status = 'published'
    """)
    Optional<Plan> findPublishedByMerchantSlugAndPlanSlug(String merchantSlug, String planSlug);

    boolean existsBySlugAndMerchantId(String slug, String merchantId);

}