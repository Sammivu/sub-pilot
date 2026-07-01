package co.subpilot.merchant.repository;

import co.subpilot.merchant.entity.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, String> {
    Optional<Merchant> findByEmail(String email);
    Optional<Merchant> findBySlug(String slug);
    boolean existsByEmail(String email);
    boolean existsBySlug(String slug);
}