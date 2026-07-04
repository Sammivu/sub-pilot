package co.subpilot.disbursement.controller;

import co.subpilot.disbursement.dto.DisbursementDtos;
import co.subpilot.disbursement.entity.Disbursement;
import co.subpilot.disbursement.service.DisbursementService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payouts")
@RequiredArgsConstructor
public class DisbursementController {

    private final DisbursementService disbursementService;

    /**
     * POST /v1/payouts/trigger — batches every net proceed owed to this
     * merchant since their last successful payout and transfers it out of
     * SubPilot's central Nomba wallet into Merchant.nombaPayoutAccountId.
     * 201 either way (a Disbursement resource was created); check `status`
     * in the body for the actual outcome, same convention as
     * POST /v1/invoices/{id}/refund.
     */
    @PostMapping("/trigger")
    public ResponseEntity<DisbursementDtos.DisbursementResponse> trigger() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userId = auth != null ? auth.getName() : null;

        Disbursement disbursement = disbursementService.trigger(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(DisbursementDtos.DisbursementResponse.from(disbursement));
    }

    @GetMapping
    public ResponseEntity<Page<DisbursementDtos.DisbursementResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(disbursementService.list(pageable).map(DisbursementDtos.DisbursementResponse::from));
    }

    @GetMapping("/{disbursementId}")
    public ResponseEntity<DisbursementDtos.DisbursementResponse> get(@PathVariable String disbursementId) {
        return ResponseEntity.ok(DisbursementDtos.DisbursementResponse.from(disbursementService.get(disbursementId)));
    }
}