package co.subpilot.auth.repository;

import co.subpilot.auth.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);
    List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(String merchantId);
    Optional<ApiKey> findByKeyHash(String keyHash);

    @Modifying
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :now WHERE k.id = :id")
    void updateLastUsed(String id, Instant now);
}