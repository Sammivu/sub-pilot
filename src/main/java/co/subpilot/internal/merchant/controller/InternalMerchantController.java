package co.subpilot.internal.merchant.controller;

import co.subpilot.internal.merchant.dto.InternalMerchantDtos;
import co.subpilot.internal.merchant.service.InternalMerchantService;
import co.subpilot.merchant.entity.Merchant;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/internal/merchants")
@RequiredArgsConstructor
public class InternalMerchantController {

    private final InternalMerchantService merchantService;

    @GetMapping
    public ResponseEntity<Page<InternalMerchantDtos.MerchantListItem>> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Merchant> merchants = merchantService.search(query, status, pageable);
        return ResponseEntity.ok(merchants.map(m ->
                InternalMerchantDtos.MerchantListItem.from(m, merchantService.resolveEffectiveFee(m))));
    }

    @GetMapping("/{merchantId}")
    public ResponseEntity<InternalMerchantDtos.MerchantDetail> getById(@PathVariable String merchantId) {
        Merchant merchant = merchantService.getById(merchantId);
        return ResponseEntity.ok(InternalMerchantDtos.MerchantDetail.from(merchant, merchantService.resolveEffectiveFee(merchant)));
    }

    @PatchMapping("/{merchantId}/status")
    public ResponseEntity<InternalMerchantDtos.MerchantDetail> updateStatus(
            @PathVariable String merchantId, @Valid @RequestBody InternalMerchantDtos.StatusUpdateRequest req) {
        Merchant merchant = merchantService.updateStatus(merchantId, req.status(), req.reason());
        return ResponseEntity.ok(InternalMerchantDtos.MerchantDetail.from(merchant, merchantService.resolveEffectiveFee(merchant)));
    }

    @GetMapping("/{merchantId}/fees")
    public ResponseEntity<InternalMerchantDtos.MerchantFeeResponse> getFees(@PathVariable String merchantId) {
        Merchant merchant = merchantService.getById(merchantId);
        var platformDefault = merchantService.getPlatformDefault();
        var effective = merchantService.resolveEffectiveFee(merchant);

        return ResponseEntity.ok(new InternalMerchantDtos.MerchantFeeResponse(
                effective.feeSource(),
                platformDefault.feeBps(), platformDefault.fixedFeeMinor(),
                merchant.getFeeBps(), merchant.getFeeFixedMinor(),
                effective.feeBps(), effective.fixedFeeMinor()
        ));
    }

    @PatchMapping("/{merchantId}/fees")
    public ResponseEntity<InternalMerchantDtos.MerchantFeeResponse> updateFees(
            @PathVariable String merchantId, @Valid @RequestBody InternalMerchantDtos.FeeOverrideRequest req) {
        merchantService.updateFeeOverride(merchantId, req.overrideFeeBps(), req.overrideFixedFeeMinor(), req.reason());
        return getFees(merchantId);
    }

    @DeleteMapping("/{merchantId}/fees")
    public ResponseEntity<InternalMerchantDtos.MerchantFeeResponse> removeFeeOverride(
            @PathVariable String merchantId, @Valid @RequestBody InternalMerchantDtos.RemoveOverrideRequest req) {
        merchantService.removeFeeOverride(merchantId, req.reason());
        return getFees(merchantId);
    }
}