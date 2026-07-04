package co.subpilot.internal.fee.controller;

import co.subpilot.internal.fee.dto.InternalFeeDtos;
import co.subpilot.internal.fee.service.InternalFeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/internal/fees/default")
@RequiredArgsConstructor
public class InternalFeeController {

    private final InternalFeeService feeService;

    @GetMapping
    public ResponseEntity<InternalFeeDtos.PlatformFeeResponse> get() {
        return ResponseEntity.ok(InternalFeeDtos.PlatformFeeResponse.from(feeService.getOrBootstrap()));
    }

    @PatchMapping
    public ResponseEntity<InternalFeeDtos.PlatformFeeResponse> update(@Valid @RequestBody InternalFeeDtos.PlatformFeeUpdateRequest req) {
        var updated = feeService.update(req.feeBps(), req.fixedFeeMinor(), req.reason());
        return ResponseEntity.ok(InternalFeeDtos.PlatformFeeResponse.from(updated));
    }
}