package co.subpilot.auth.repository;

import co.subpilot.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    Optional<User> findByMerchantIdAndEmail(String merchantId, String email);
    boolean existsByEmail(String email);
    Optional<User> findByRefreshTokenHash(String refreshTokenHash);

}