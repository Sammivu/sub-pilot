package co.subpilot.webhook.repository;

import co.subpilot.webhook.entity.WebhookDelivery;
import co.subpilot.webhook.entity.WebhookEndpoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, String> {
    List<WebhookEndpoint> findByMerchantIdAndIsActiveTrue(String merchantId);
    Optional<WebhookEndpoint> findByIdAndMerchantId(String id, String merchantId);
    Page<WebhookEndpoint> findByMerchantId(String merchantId, Pageable pageable);

    List<WebhookEndpoint> findByMerchantId(String merchantId);

    List<WebhookEndpoint> findByMerchantIdAndActiveTrue(String merchantId);
}