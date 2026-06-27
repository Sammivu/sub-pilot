package co.subpilot.event.service;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.event.entity.Event;
import co.subpilot.event.repository.EventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Single entry point for writing to the append-only event log.
 *
 * All event types from the PRD §6.10 are recorded through this service.
 * Webhook delivery, analytics, and audit all read from here.
 *
 * Runs in its own transaction (REQUIRES_NEW) so an event-log failure never
 * rolls back the primary business operation that triggered it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Event record(String merchantId, String type, String resourceType, String resourceId,
                        Map<String, Object> payload) {
        return recordWithSubscription(merchantId, type, resourceType, resourceId, null, payload);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Event recordWithSubscription(String merchantId, String type, String resourceType, String resourceId,
                                        String subscriptionId, Map<String, Object> payload) {
        try {
            Event event = new Event();
            event.setId(UlidCreator.getMonotonicUlid().toString());
            event.setMerchantId(merchantId);
            event.setType(type);
            event.setResourceType(resourceType);
            event.setResourceId(resourceId);
            event.setSubscriptionId(subscriptionId);
            event.setPayload(objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
            event.setCreatedAt(Instant.now());
            Event saved = eventRepository.save(event);

            // Notify listeners (webhook dispatch) only after the surrounding
            // transaction commits successfully — avoids delivering webhooks
            // for events that get rolled back.
            applicationEventPublisher.publishEvent(new EventCreated(saved));

            return saved;
        } catch (Exception e) {
            // Never let event-log failures break the calling business operation.
            log.error("Failed to record event type={} merchant={}: {}", type, merchantId, e.getMessage(), e);
            return null;
        }
    }

    /** Lightweight domain event published after every Event row is persisted. */
    public record EventCreated(Event event) {}

    /**
     * Alias for record() — used throughout billing/dunning/subscription services.
     * Kept separate from record()/recordWithSubscription() so call sites reading
     * "emit an event" stay readable; behaviour is identical.
     */
    public Event emit(String merchantId, String type, String resourceType, String resourceId, Map<String, Object> payload) {
        return record(merchantId, type, resourceType, resourceId, payload);
    }

    public Page<Event> list(String type, String subscriptionId, int page, int perPage) {
        String merchantId = TenantContext.requireMerchantId();
        Pageable pageable = PageRequest.of(page, Math.min(perPage, 100), Sort.by("createdAt").descending());

        if (subscriptionId != null && !subscriptionId.isBlank()) {
            return eventRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(merchantId, subscriptionId, pageable);
        }

        if (type != null && !type.isBlank()) {
            return eventRepository.findByMerchantIdAndTypeOrderByCreatedAtDesc(merchantId, type, pageable);
        }
        return eventRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }
}