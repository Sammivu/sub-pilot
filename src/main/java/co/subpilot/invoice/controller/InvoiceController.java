package co.subpilot.invoice.controller;

import co.subpilot.common.tenant.TenantContext;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.refund.dto.RefundDtos;
import co.subpilot.refund.entity.Refund;
import co.subpilot.refund.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final RefundService refundService;

    @GetMapping
    public ResponseEntity<Page<Invoice>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String subscriptionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.list(status, q, subscriptionId, page, size));
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

    /** POST /v1/invoices/{id}/refund — full refund if `amount` is omitted, partial otherwise. */
    @PostMapping("/{invoiceId}/refund")
    public ResponseEntity<RefundDtos.RefundResponse> refund(
            @PathVariable String invoiceId,
            @Valid @RequestBody(required = false) RefundDtos.CreateRefundRequest req
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth != null ? auth.getName() : null;

        Long amount = req != null ? req.amount() : null;
        String reason = req != null ? req.reason() : null;

        Refund refund = refundService.createRefund(invoiceId, amount, reason, userId);
        HttpStatus status = HttpStatus.CREATED; // 201 regardless of succeeded/failed outcome — the refund *resource* was created either way; check .status() in the body for the outcome
        return ResponseEntity.status(status).body(RefundDtos.RefundResponse.from(refund));
    }

    /** GET /v1/invoices/{id}/refund — refund history for this invoice (usually 0 or 1 entries; more if partially refunded multiple times). */
    @GetMapping("/{invoiceId}/refund")
    public ResponseEntity<List<RefundDtos.RefundResponse>> getRefunds(@PathVariable String invoiceId) {
        List<RefundDtos.RefundResponse> refunds = refundService.listForInvoice(invoiceId).stream()
                .map(RefundDtos.RefundResponse::from)
                .toList();
        return ResponseEntity.ok(refunds);
    }
}