package co.subpilot.event.controller;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.event.entity.Event;
import co.subpilot.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ResponseEntity<Page<Event>> list(@RequestParam(required = false) String type,
            @RequestParam(required = false) String subscriptionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(eventService.list(type, subscriptionId, page, size));
    }
}