package co.subpilot.customer.controller;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.customer.dto.CustomerDtos;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.service.CustomerService;
import co.subpilot.utils.PaginationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Set;

import static co.subpilot.utils.PaginationUtils.parseSort;

/**
 * Maps to /app/customers in the frontend.
 */
@RestController
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private static final Set<String> SORTABLE_FIELDS = Set.of("fullName", "email", "phone", "createdAt");

    @GetMapping
    public ResponseEntity<Page<Customer>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int perPage,
            @RequestParam(required = false) String sort) {
        String merchantId = TenantContext.requireMerchantId();

        var pageable = PaginationUtils.pageable(page, perPage, sort, SORTABLE_FIELDS,
                Collections.emptyMap(), "createdAt");

        return ResponseEntity.ok(customerService.search(merchantId, q, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerDtos.CustomerDetailResponse> get(@PathVariable String id) {
        String merchantId = TenantContext.requireMerchantId();
        Customer customer = customerService.getOwned(merchantId, id);

        var savedCards = customerService.fetchSavedCards(customer.getEmail()).stream()
                .map(CustomerDtos.SavedCard::from)
                .toList();

        return ResponseEntity.ok(CustomerDtos.CustomerDetailResponse.from(customer, savedCards));
    }
}