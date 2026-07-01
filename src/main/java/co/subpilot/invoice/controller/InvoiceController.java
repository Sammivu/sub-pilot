package co.subpilot.invoice.controller;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    @GetMapping
    public ResponseEntity<Page<Invoice>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
//        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(invoiceService.list(status,  page,  size));
    }

    @GetMapping("/{invoiceId}")
    public ResponseEntity<Invoice> getById(@PathVariable String invoiceId) {
        String merchantId = TenantContext.requireMerchantId();

        return ResponseEntity.ok(invoiceService.getOwned(merchantId ,invoiceId));
    }

    @PostMapping("/{invoiceId}/void")
    public ResponseEntity<Map<String, String>> voidInvoice(@PathVariable String invoiceId) {
        String merchantId = TenantContext.requireMerchantId();
        invoiceService.voidInvoice(merchantId, invoiceId);
        return ResponseEntity.ok(Map.of("message", "Invoice voided successfully."));
    }
}