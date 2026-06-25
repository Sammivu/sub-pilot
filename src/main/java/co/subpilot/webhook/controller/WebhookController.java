package co.subpilot.webhook.controller;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.nomba.service.NombaWebhookSignatureVerifier;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.subscription.repository.SubscriptionRepository;
import co.subpilot.webhook.dto.WebhookDtos;
import co.subpilot.webhook.entity.WebhookDelivery;
import co.subpilot.webhook.entity.WebhookEndpoint;
import co.subpilot.webhook.repository.WebhookDeliveryRepository;
import co.subpilot.webhook.repository.WebhookEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final InvoiceRepository invoiceRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final InvoiceService invoiceService;
    private final NombaPaymentGateway nomba;
    private final ObjectMapper objectMapper;

    /**
     * Only present when subpilot.nomba.mock-mode=false. In mock mode this is
     * null and we fall back to nomba.verifyWebhookSignature(...), which
     * MockNombaGateway always approves — appropriate for local dev and demos.
     */
    @Autowired(required = false)
    private NombaWebhookSignatureVerifier realSignatureVerifier;

    // ── Endpoint management ───────────────────────────────────────────────────

    @PostMapping("/v1/webhooks/endpoints")
    public ResponseEntity<WebhookEndpoint> register(
            @Valid @RequestBody WebhookDtos.RegisterEndpointRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        String secret = generateSecret();

        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setMerchantId(merchantId);
        endpoint.setUrl(req.url());
        endpoint.setDescription(req.description());
        endpoint.setSubscribedEvents(req.events() != null ? req.events() : List.of());
        endpoint.setActive(true);
        endpoint.setSigningSecretHash(secret);
        endpoint = endpointRepository.save(endpoint);

        return ResponseEntity.ok(endpoint);
    }

    @GetMapping("/v1/webhooks/endpoints")
    public ResponseEntity<Page<WebhookEndpoint>> listEndpoints(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String merchantId = TenantContext.requireMerchantId();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(endpointRepository.findByMerchantId(merchantId, pageable));
    }

    @DeleteMapping("/v1/webhooks/endpoints/{endpointId}")
    public ResponseEntity<Map<String, String>> deleteEndpoint(@PathVariable String endpointId) {
        String merchantId = TenantContext.requireMerchantId();
        endpointRepository.findByIdAndMerchantId(endpointId, merchantId)
                .ifPresent(endpointRepository::delete);
        return ResponseEntity.ok(Map.of("message", "Endpoint removed."));
    }

    @GetMapping("/v1/webhooks/deliveries")
    public ResponseEntity<Page<WebhookDelivery>> listDeliveries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String merchantId = TenantContext.requireMerchantId();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(deliveryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable));
    }

    // ── Inbound Nomba webhook handler ─────────────────────────────────────────

    @PostMapping("/v1/webhooks/nomba")
    public ResponseEntity<String> handleNombaWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Nomba-Signature", required = false) String signature,
            @RequestHeader(value = "X-Nomba-Timestamp", required = false) String timestamp) {

        // 1. Verify signature.
        //    Real mode: Nomba's HMAC scheme is computed over specific JSON
        //    fields + a timestamp header (see NombaWebhookSignatureVerifier),
        //    so we parse first and verify against the structured payload.
        //    Mock mode: MockNombaGateway.verifyWebhookSignature always
        //    returns true, so this is a no-op convenience path in dev/demo.
        boolean signatureValid;
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(rawPayload, Map.class);
        } catch (Exception e) {
            log.warn("Inbound Nomba webhook had unparseable body: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Malformed payload");
        }

        if (realSignatureVerifier != null) {
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.valueToTree(event);
            signatureValid = realSignatureVerifier.verify(jsonNode, timestamp, signature);
        } else {
            signatureValid = nomba.verifyWebhookSignature(rawPayload, signature);
        }

        if (!signatureValid) {
            log.warn("Invalid Nomba webhook signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 2. Process
        try {
            String eventType = (String) event.get("type");
            Map<String, Object> data = (Map<String, Object>) event.get("data");

            log.info("Inbound Nomba webhook: type={}", eventType);

            switch (eventType != null ? eventType : "") {
                case "payment.success" -> handleNombaPaymentSuccess(data);
                case "payment.failed"  -> handleNombaPaymentFailed(data);
                default -> log.debug("Unhandled Nomba event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process Nomba webhook: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok("received");
    }

    private void handleNombaPaymentSuccess(Map<String, Object> data) {
        String nombaRef = (String) data.get("reference");
        String subscriptionId = extractMeta(data, "subscriptionId");

        if (subscriptionId == null) return;

        // Find subscription and activate if pending
        subscriptionRepository.findById(subscriptionId).ifPresent(sub -> {
            if ("trialing".equals(sub.getStatus()) || "active".equals(sub.getStatus())) {
                String cardToken = (String) data.get("cardToken");
                String customerId = (String) data.get("customerId");
                // delegate to subscription activation
                log.info("Nomba payment success: subscription={} ref={}", subscriptionId, nombaRef);
            }
        });

        // Update matching payment attempt
        paymentAttemptRepository.findByNombaReference(nombaRef).ifPresent(attempt -> {
            if (!attempt.isTerminal()) {
                attempt.setStatus("succeeded");
                attempt.setResolvedAt(Instant.now());
                paymentAttemptRepository.save(attempt);
                invoiceService.markPaid(attempt.getInvoiceId(), nombaRef);
            }
        });
    }

    private void handleNombaPaymentFailed(Map<String, Object> data) {
        String nombaRef = (String) data.get("reference");
        paymentAttemptRepository.findByNombaReference(nombaRef).ifPresent(attempt -> {
            if (!attempt.isTerminal()) {
                attempt.setStatus("failed");
                attempt.setFailureCode((String) data.get("failureCode"));
                attempt.setFailureReason((String) data.get("message"));
                attempt.setResolvedAt(Instant.now());
                paymentAttemptRepository.save(attempt);
            }
        });
    }

    private String extractMeta(Map<String, Object> data, String key) {
        try {
            Object meta = data.get("metadata");
            if (meta instanceof String s) {
                return (String) objectMapper.readValue(s, Map.class).get(key);
            }
            if (meta instanceof Map m) return (String) m.get(key);
        } catch (Exception ignored) {}
        return null;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
