package co.subpilot.customer.controller;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Maps to /app/customers in the frontend.
 */
@RestController
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ResponseEntity<Page<Customer>> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int perPage) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(customerService.list(merchantId, PageRequest.of(page, Math.min(perPage, 100))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Customer> get(@PathVariable String id) {
        String merchantId = TenantContext.requireMerchantId();
        return ResponseEntity.ok(customerService.getOwned(merchantId, id));
    }
}