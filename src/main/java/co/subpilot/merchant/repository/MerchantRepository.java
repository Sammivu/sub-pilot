package co.subpilot.merchant.repository;

import co.subpilot.merchant.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {
    Optional<Merchant> findByEmail(String email);
    Optional<Merchant> findBySlug(String slug);
    boolean existsByEmail(String email);
    boolean existsBySlug(String slug);

    long countByStatus(String status);

    /**
     * Internal admin merchant directory search — "search by business name,
     * email, merchant ID, slug; filter by status" per the admin spec.
     * query is matched (case-insensitive, substring) against businessName,
     * email, id, and slug all at once — the spec doesn't distinguish a
     * separate field-specific search, just "search", so one query box
     * covering all four identifiers is the intended behavior.
     */
    @Query("SELECT m FROM Merchant m WHERE " +
            "(:query IS NULL OR " +
            " LOWER(m.businessName) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR " +
            " LOWER(m.email) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR " +
            " LOWER(m.id) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%')) OR " +
            " LOWER(m.slug) LIKE LOWER(CONCAT('%', CAST(:query AS string), '%'))) " +
            "AND (:status IS NULL OR m.status = :status)")
    Page<Merchant> search(@Param("query") String query, @Param("status") String status, Pageable pageable);
}