//package co.subpilot.webhook.controller;
//
//import co.subpilot.audit.AuditAction;
//import co.subpilot.audit.service.AuditLogService;
//import co.subpilot.common.tenant.TenantContext;
//import co.subpilot.dunning.service.DunningTriggerService;
//import co.subpilot.invoice.repository.InvoiceRepository;
//import co.subpilot.invoice.service.InvoiceService;
//import co.subpilot.nomba.CheckoutPurpose;
//import co.subpilot.nomba.NombaPaymentGateway;
//import co.subpilot.nomba.service.NombaWebhookSignatureVerifier;
//import co.subpilot.payment.repository.PaymentAttemptRepository;
//import co.subpilot.subscription.enums.SubscriptionStatus;
//import co.subpilot.subscription.repository.SubscriptionRepository;
//import co.subpilot.subscription.service.SubscriptionService;
//import co.subpilot.webhook.dto.WebhookDtos;
//import co.subpilot.webhook.entity.WebhookDelivery;
//import co.subpilot.webhook.entity.WebhookEndpoint;
//import co.subpilot.webhook.repository.WebhookDeliveryRepository;
//import co.subpilot.webhook.repository.WebhookEndpointRepository;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.domain.Page;
//import org.springframework.data.domain.PageRequest;
//import org.springframework.data.domain.Sort;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//
//import java.security.SecureRandom;
//import java.time.Instant;
//import java.util.Base64;
//import java.util.List;
//import java.util.Map;
//
//@Slf4j
//@RestController
//@RequiredArgsConstructor
//public class WebhookController {
//
//    private final WebhookEndpointRepository endpointRepository;
//    private final WebhookDeliveryRepository deliveryRepository;
//    private final PaymentAttemptRepository paymentAttemptRepository;
//    private final InvoiceRepository invoiceRepository;
//    private final SubscriptionRepository subscriptionRepository;
//    private final InvoiceService invoiceService;
//    private final SubscriptionService subscriptionService;
//    private final DunningTriggerService dunningTriggerService;
//    private final NombaPaymentGateway nomba;
//    private final ObjectMapper objectMapper;
//    private final AuditLogService auditLogService;
//
//    /**
//     * Only present when subpilot.nomba.mock-mode=false. In mock mode this is
//     * null and we fall back to nomba.verifyWebhookSignature(...), which
//     * MockNombaGateway always approves — appropriate for local dev and demos.
//     */
//    @Autowired(required = false)
//    private NombaWebhookSignatureVerifier realSignatureVerifier;
//
//    // ── Endpoint management ───────────────────────────────────────────────────
//
//    @PostMapping("/v1/webhooks/endpoints")
//    public ResponseEntity<WebhookEndpoint> register(
//            @Valid @RequestBody WebhookDtos.RegisterEndpointRequest req) {
//        String merchantId = TenantContext.requireMerchantId();
//        String secret = generateSecret();
//
//        WebhookEndpoint endpoint = new WebhookEndpoint();
//        endpoint.setMerchantId(merchantId);
//        endpoint.setUrl(req.url());
//        endpoint.setDescription(req.description());
//        endpoint.setSubscribedEvents(req.events() != null ? req.events() : List.of());
//        endpoint.setActive(true);
//        endpoint.setSigningSecretHash(secret);
//        endpoint = endpointRepository.save(endpoint);
//
//        auditLogService.recordCreation(merchantId, AuditAction.WEBHOOK_ENDPOINT_CREATED,
//                "webhook_endpoint", endpoint.getId(),
//                Map.of("url", endpoint.getUrl(), "events", endpoint.getSubscribedEvents()));
//
//        return ResponseEntity.ok(endpoint);
//    }
//
//    @GetMapping("/v1/webhooks/endpoints")
//    public ResponseEntity<Page<WebhookEndpoint>> listEndpoints(
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "20") int size) {
//        String merchantId = TenantContext.requireMerchantId();
//        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
//        return ResponseEntity.ok(endpointRepository.findByMerchantId(merchantId, pageable));
//    }
//
//    @DeleteMapping("/v1/webhooks/endpoints/{endpointId}")
//    public ResponseEntity<Map<String, String>> deleteEndpoint(@PathVariable String endpointId) {
//        String merchantId = TenantContext.requireMerchantId();
//        endpointRepository.findByIdAndMerchantId(endpointId, merchantId)
//                .ifPresent(endpoint -> {
//                    auditLogService.recordDeletion(merchantId, AuditAction.WEBHOOK_ENDPOINT_DELETED,
//                            "webhook_endpoint", endpointId,
//                            Map.of("url", endpoint.getUrl(), "events", endpoint.getSubscribedEvents()));
//                    endpointRepository.delete(endpoint);
//                });
//        return ResponseEntity.ok(Map.of("message", "Endpoint removed."));
//    }
//
//    @GetMapping("/v1/webhooks/deliveries")
//    public ResponseEntity<Page<WebhookDelivery>> listDeliveries(
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "20") int size) {
//        String merchantId = TenantContext.requireMerchantId();
//        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
//        return ResponseEntity.ok(deliveryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable));
//    }
//
//    // ── Inbound Nomba webhook handler ─────────────────────────────────────────
//
//    @PostMapping("/v1/webhooks/nomba")
//    public ResponseEntity<String> handleNombaWebhook(
//            @RequestBody String rawPayload,
//            @RequestHeader(value = "X-Nomba-Signature", required = false) String signature,
//            @RequestHeader(value = "X-Nomba-Timestamp", required = false) String timestamp) {
//
//        // 1. Verify signature.
//        //    Real mode: Nomba's HMAC scheme is computed over specific JSON
//        //    fields + a timestamp header (see NombaWebhookSignatureVerifier),
//        //    so we parse first and verify against the structured payload.
//        //    Mock mode: MockNombaGateway.verifyWebhookSignature always
//        //    returns true, so this is a no-op convenience path in dev/demo.
//        boolean signatureValid;
//        Map<String, Object> event;
//        try {
//            event = objectMapper.readValue(rawPayload, Map.class);
//        } catch (Exception e) {
//            log.warn("Inbound Nomba webhook had unparseable body: {}", e.getMessage());
//            return ResponseEntity.badRequest().body("Malformed payload");
//        }
//
//        if (realSignatureVerifier != null) {
//            JsonNode jsonNode = objectMapper.valueToTree(event);
//            signatureValid = realSignatureVerifier.verify(jsonNode, timestamp, signature);
//        } else {
//            signatureValid = nomba.verifyWebhookSignature(rawPayload, signature);
//        }
//
//        if (!signatureValid) {
//            log.warn("Invalid Nomba webhook signature");
//            return ResponseEntity.status(401).body("Invalid signature");
//        }
//
//        // 2. Process
//        try {
//            String eventType = (String) event.get("event_type");
//            Map<String, Object> data = (Map<String, Object>) event.get("data");
//
//            log.info("Inbound Nomba webhook: type={}", eventType);
//
//            // Nomba's documented webhook payload uses "payment_success"
//            // (snake_case) for the event_type field — confirmed against
//            // multiple real sandbox/production examples on developer.nomba.com.
//            // A "payment_failed" event for checkout-based card payments is
//            // NOT documented anywhere we could confirm; Nomba's own guidance
//            // repeatedly stresses verifying server-side rather than relying
//            // on a failure webhook ("Always verify the transaction
//            // server-side... Do not rely on the webhook alone"). The case
//            // below is kept as defensive handling in case such an event
//            // does exist under this name, but its absence from the docs
//            // means checkout failures are primarily just "no webhook ever
//            // arrives" rather than an explicit failure notification — there
//            // is nothing to actively react to in that case.
//            switch (eventType != null ? eventType : "") {
//                case "payment_success" -> handleNombaPaymentSuccess(data);
//                case "payment_failed"  -> handleNombaPaymentFailed(data);
//                default -> log.debug("Unhandled Nomba event type: {}", eventType);
//            }
//        } catch (Exception e) {
//            log.error("Failed to process Nomba webhook: {}", e.getMessage(), e);
//        }
//
//        return ResponseEntity.ok("received");
//    }
//
//    /**
//     * Real Nomba payment_success/payment.failed payload shape (confirmed
//     * against developer.nomba.com's documented and sandbox examples):
//     *
//     *   {
//     *     "event_type": "payment_success",
//     *     "requestId": "...",
//     *     "data": {
//     *       "merchant": { "userId": "...", "walletId": "...", "walletBalance": ... },
//     *       "transaction": { "transactionId": "...", "type": "...", "transactionAmount": ..., "fee": ..., "time": "..." },
//     *       "order": { "orderReference": "...", "amount": ..., "currency": "...", "accountId": "...", "customerEmail": "..." },
//     *       "tokenizedCardData": { "tokenKey": "...", "cardType": "...", "cardPan": "..." }
//     *     }
//     *   }
//     *
//     * Notably absent: any "orderMetaData" echo, "reference" at the top of
//     * data, "cardToken"/"customerId" flat fields, or a String literal for
//     * subscription status — all of which the previous version of this
//     * handler incorrectly assumed. See CheckoutPurpose for how routing
//     * actually works now (orderReference prefix, not metadata).
//     */
//    private void handleNombaPaymentSuccess(Map<String, Object> data) {
//        Map<String, Object> order = asMap(data.get("order"));
//        Map<String, Object> transaction = asMap(data.get("transaction"));
//        Map<String, Object> tokenizedCardData = asMap(data.get("tokenizedCardData"));
//
//        String orderReference = order != null ? (String) order.get("orderReference") : null;
//        String transactionId = transaction != null ? (String) transaction.get("transactionId") : null;
//        String cardToken = tokenizedCardData != null ? (String) tokenizedCardData.get("tokenKey") : null;
//
//        if (orderReference == null) {
//            log.warn("Nomba payment_success webhook missing data.order.orderReference — cannot route, ignoring");
//            return;
//        }
//
//        String subscriptionId = CheckoutPurpose.extractSubscriptionId(orderReference);
//        if (subscriptionId == null) {
//            log.debug("Nomba payment_success for orderReference={} does not match a known prefix — ignoring", orderReference);
//            return;
//        }
//
//        if (CheckoutPurpose.isCardUpdate(orderReference)) {
//            handleCardUpdateSuccess(subscriptionId, cardToken, order);
//            return;
//        }
//
//        if (CheckoutPurpose.isNewSubscription(orderReference)) {
//            handleNewSubscriptionSuccess(subscriptionId, cardToken, order, transactionId);
//        }
//
//        // Resolve the matching PaymentAttempt, if one exists for this
//        // transaction (recurring renewal/dunning charges go through
//        // PaymentService, which always sets nombaReference — initial
//        // checkout-driven activations do not create a PaymentAttempt row at
//        // all, so finding none here for a NEW_SUBSCRIPTION webhook is
//        // expected and not an error).
//        if (transactionId != null) {
//            paymentAttemptRepository.findByNombaReference(transactionId).ifPresent(attempt -> {
//                if (!attempt.isTerminal()) {
//                    attempt.setStatus("succeeded");
//                    attempt.setResolvedAt(Instant.now());
//                    paymentAttemptRepository.save(attempt);
//                    invoiceService.markPaid(attempt.getInvoiceId(), transactionId);
//                }
//            });
//        }
//    }
//
//    /**
//     * The subscriber clicked "update payment method" in the portal, completed
//     * a fresh Nomba checkout, and we now have a new card token — hand off to
//     * DunningTriggerService.resolveViaSelfCure, which updates the stored
//     * token AND immediately retries the currently-failing charge if there is
//     * an active dunning execution for this subscription.
//     */
//    private void handleCardUpdateSuccess(String subscriptionId, String cardToken, Map<String, Object> order) {
//        if (cardToken == null) {
//            log.warn("Card-update checkout succeeded for subscription={} but no tokenKey was present in the webhook", subscriptionId);
//            return;
//        }
//
//        subscriptionRepository.findById(subscriptionId).ifPresentOrElse(sub -> {
//            String customerId = order != null ? (String) order.get("accountId") : null;
//            log.info("Card update succeeded via self-cure: subscription={}", subscriptionId);
//            dunningTriggerService.resolveViaSelfCure(sub.getSubscriptionToken(), cardToken, customerId);
//        }, () -> log.warn("Card-update webhook referenced unknown subscription={}", subscriptionId));
//    }
//
//    /**
//     * The subscriber completed their very first checkout for a brand-new
//     * subscription — store the card token and activate.
//     */
//    private void handleNewSubscriptionSuccess(String subscriptionId, String cardToken, Map<String, Object> order, String transactionId) {
//        subscriptionRepository.findById(subscriptionId).ifPresentOrElse(sub -> {
//            if (sub.getStatus() == SubscriptionStatus.trialing
//                    || sub.getStatus() == SubscriptionStatus.active) {
//                String customerId = order != null ? (String) order.get("accountId") : null;
//                log.info("Nomba new-subscription checkout succeeded: subscription={} transactionId={}",
//                        subscriptionId, transactionId);
//                subscriptionService.activateAfterCheckout(subscriptionId, cardToken, transactionId, customerId);
//            } else {
//                log.debug("New-subscription webhook for subscription={} ignored — status is already {}",
//                        subscriptionId, sub.getStatus());
//            }
//        }, () -> log.warn("New-subscription webhook referenced unknown subscription={}", subscriptionId));
//    }
//
//    @SuppressWarnings("unchecked")
//    private Map<String, Object> asMap(Object value) {
//        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
//    }
//
//    private void handleNombaPaymentFailed(Map<String, Object> data) {
//        Map<String, Object> transaction = asMap(data.get("transaction"));
//        String transactionId = transaction != null ? (String) transaction.get("transactionId") : null;
//        String responseCode = transaction != null ? (String) transaction.get("responseCode") : null;
//
//        if (transactionId == null) {
//            log.warn("Nomba payment_failed webhook missing data.transaction.transactionId — cannot match, ignoring");
//            return;
//        }
//
//        paymentAttemptRepository.findByNombaReference(transactionId).ifPresent(attempt -> {
//            if (!attempt.isTerminal()) {
//                attempt.setStatus("failed");
//                attempt.setFailureCode(responseCode);
//                attempt.setFailureReason("Nomba reported payment_failed for transaction " + transactionId);
//                attempt.setResolvedAt(Instant.now());
//                paymentAttemptRepository.save(attempt);
//            }
//        });
//    }
//
//    private String generateSecret() {
//        byte[] bytes = new byte[32];
//        new SecureRandom().nextBytes(bytes);
//        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
//    }
//}

package co.subpilot.webhook.controller;

import co.subpilot.audit.AuditAction;
import co.subpilot.audit.service.AuditLogService;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.dunning.service.DunningTriggerService;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.nomba.CheckoutPurpose;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.nomba.service.NombaReconciliationService;
import co.subpilot.nomba.service.NombaWebhookSignatureVerifier;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.repository.SubscriptionRepository;
import co.subpilot.subscription.service.SubscriptionService;
import co.subpilot.webhook.dto.WebhookDtos;
import co.subpilot.webhook.entity.WebhookDelivery;
import co.subpilot.webhook.entity.WebhookEndpoint;
import co.subpilot.webhook.repository.WebhookDeliveryRepository;
import co.subpilot.webhook.repository.WebhookEndpointRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final SubscriptionService subscriptionService;
    private final DunningTriggerService dunningTriggerService;
    private final NombaPaymentGateway nomba;
    private final NombaReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

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

        auditLogService.recordCreation(merchantId, AuditAction.WEBHOOK_ENDPOINT_CREATED,
                "webhook_endpoint", endpoint.getId(),
                Map.of("url", endpoint.getUrl(), "events", endpoint.getSubscribedEvents()));

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
                .ifPresent(endpoint -> {
                    auditLogService.recordDeletion(merchantId, AuditAction.WEBHOOK_ENDPOINT_DELETED,
                            "webhook_endpoint", endpointId,
                            Map.of("url", endpoint.getUrl(), "events", endpoint.getSubscribedEvents()));
                    endpointRepository.delete(endpoint);
                });
        return ResponseEntity.ok(Map.of("message", "Endpoint removed."));
    }

    @GetMapping("/v1/webhooks/deliveries")
    public ResponseEntity<Page<WebhookDelivery>> listDeliveries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String endpointId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String merchantId = TenantContext.requireMerchantId();
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<WebhookDelivery> deliveries = deliveryRepository.search(merchantId, status, endpointId, eventType, pageable);
        log.info("Listing deliveries for merchant={}", merchantId);
        return ResponseEntity.ok(deliveries);
    }

    // ── Inbound Nomba webhook handler ─────────────────────────────────────────

    /**
     * Header names confirmed against Nomba's sandbox testing docs
     * (developer.nomba.com/docs/products/accept-payment/sandbox-testing) —
     * NOT X-Nomba-Signature/X-Nomba-Timestamp, which don't exist on a real
     * Nomba webhook and were silently swallowing every real inbound
     * webhook as a 401 (masked in testing so far because mock mode
     * bypasses verification entirely).
     *
     * nomba-signature is confirmed as the header to verify — straight from
     * Nomba's own dashboard copy: "We sign every webhook we forward to
     * you. Use this key to verify the nomba-signature header so you know
     * it genuinely came from Nomba." That's the authoritative source here,
     * ahead of the docs cross-reference ambiguity this used to carry.
     * nomba-sig-value is kept only as a harmless defensive fallback, not
     * because it's equally likely to be the right one.
     */
    @PostMapping("/v1/webhooks/nomba")
    public ResponseEntity<String> handleNombaWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "nomba-signature", required = false) String signature,
            @RequestHeader(value = "nomba-sig-value", required = false) String signatureValueFallback,
            @RequestHeader(value = "nomba-timestamp", required = false) String timestamp) {

        log.info("Nomba Raw WEBHOOK payload incoming sig{}====", signature);
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
            JsonNode jsonNode = objectMapper.valueToTree(event);
            signatureValid = realSignatureVerifier.verify(jsonNode, timestamp, signature);
            if (!signatureValid && signatureValueFallback != null) {
                signatureValid = realSignatureVerifier.verify(jsonNode, timestamp, signatureValueFallback);
            }
        } else {
            signatureValid = nomba.verifyWebhookSignature(rawPayload, signature);
        }

        if (!signatureValid) {
            log.warn("Invalid Nomba webhook signature");
            return ResponseEntity.status(401).body("Invalid signature");
        }

        // 2. Process
        try {
            String eventType = (String) event.get("event_type");
            Map<String, Object> data = (Map<String, Object>) event.get("data");

            log.info("Inbound Nomba webhook: type={}", eventType);

            // Nomba's documented webhook payload uses "payment_success"
            // (snake_case) for the event_type field — confirmed against
            // multiple real sandbox/production examples on developer.nomba.com.
            // A "payment_failed" event for checkout-based card payments is
            // NOT documented anywhere we could confirm; Nomba's own guidance
            // repeatedly stresses verifying server-side rather than relying
            // on a failure webhook ("Always verify the transaction
            // server-side... Do not rely on the webhook alone"). The case
            // below is kept as defensive handling in case such an event
            // does exist under this name, but its absence from the docs
            // means checkout failures are primarily just "no webhook ever
            // arrives" rather than an explicit failure notification — there
            // is nothing to actively react to in that case.
            switch (eventType != null ? eventType : "") {
                case "payment_success" -> handleNombaPaymentSuccess(data);
                case "payment_failed"  -> handleNombaPaymentFailed(data);
                default -> log.debug("Unhandled Nomba event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process Nomba webhook: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok("received");
    }

    /**
     * Real Nomba payment_success/payment.failed payload shape (confirmed
     * against developer.nomba.com's documented and sandbox examples):
     *
     *   {
     *     "event_type": "payment_success",
     *     "requestId": "...",
     *     "data": {
     *       "merchant": { "userId": "...", "walletId": "...", "walletBalance": ... },
     *       "transaction": { "transactionId": "...", "type": "...", "transactionAmount": ..., "fee": ..., "time": "..." },
     *       "order": { "orderReference": "...", "amount": ..., "currency": "...", "accountId": "...", "customerEmail": "..." },
     *       "tokenizedCardData": { "tokenKey": "...", "cardType": "...", "cardPan": "..." }
     *     }
     *   }
     *
     * Notably absent: any "orderMetaData" echo, "reference" at the top of
     * data, "cardToken"/"customerId" flat fields, or a String literal for
     * subscription status — all of which the previous version of this
     * handler incorrectly assumed. See CheckoutPurpose for how routing
     * actually works now (orderReference prefix, not metadata).
     */
    private void handleNombaPaymentSuccess(Map<String, Object> data) {
        Map<String, Object> order = asMap(data.get("order"));
        Map<String, Object> transaction = asMap(data.get("transaction"));
        Map<String, Object> tokenizedCardData = asMap(data.get("tokenizedCardData"));

        String orderReference = order != null ? (String) order.get("orderReference") : null;
        String transactionId = transaction != null ? (String) transaction.get("transactionId") : null;
        String cardToken = tokenizedCardData != null ? (String) tokenizedCardData.get("tokenKey") : null;

        if (orderReference == null) {
            log.warn("Nomba payment_success webhook missing data.order.orderReference — cannot route, ignoring");
            return;
        }

        String subscriptionId = CheckoutPurpose.extractSubscriptionId(orderReference);
        if (subscriptionId == null) {
            log.debug("Nomba payment_success for orderReference={} does not match a known prefix — ignoring", orderReference);
            return;
        }

        if (CheckoutPurpose.isCardUpdate(orderReference)) {
            reconciliationService.resolveCardUpdateCheckout(subscriptionId, cardToken,
                    order != null ? (String) order.get("accountId") : null, transactionId, "webhook");
            return;
        }

        if (CheckoutPurpose.isNewSubscription(orderReference)) {
            reconciliationService.resolveNewSubscriptionCheckout(subscriptionId, cardToken,
                    transactionId, order != null ? (String) order.get("accountId") : null, "webhook");
        }

        // Resolve the matching PaymentAttempt, if one exists for this
        // transaction (recurring renewal/dunning charges go through
        // PaymentService, which always sets nombaReference — initial
        // checkout-driven activations do not create a PaymentAttempt row at
        // all, so finding none here for a NEW_SUBSCRIPTION webhook is
        // expected and not an error).
        if (transactionId != null) {
            reconciliationService.resolvePaymentAttempt(transactionId, true, null, null, "webhook");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private void handleNombaPaymentFailed(Map<String, Object> data) {
        Map<String, Object> transaction = asMap(data.get("transaction"));
        String transactionId = transaction != null ? (String) transaction.get("transactionId") : null;
        String responseCode = transaction != null ? (String) transaction.get("responseCode") : null;

        if (transactionId == null) {
            log.warn("Nomba payment_failed webhook missing data.transaction.transactionId — cannot match, ignoring");
            return;
        }

        reconciliationService.resolvePaymentAttempt(transactionId, false, responseCode,
                "Nomba reported payment_failed for transaction " + transactionId, "webhook");
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}