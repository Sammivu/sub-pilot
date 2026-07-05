package co.subpilot.merchant.controller;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.nomba.NombaPaymentGateway;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Bank lookup gap fix: nothing previously called
 * NombaPaymentGateway.lookupBankAccount before a merchant's payout details
 * were saved — a typo'd account number would silently save, and the first
 * anyone would find out is a failed (or worse, misdirected) real transfer.
 * PATCH /payout-account now requires a successful lookup first, and always
 * persists Nomba's OWN resolved account name — never whatever the merchant
 * typed — so Merchant.payoutAccountName can never drift from reality.
 */
@RestController
@RequestMapping("/v1/merchants/me")
@RequiredArgsConstructor
public class MerchantSettingsController {

    private final MerchantRepository merchantRepository;
    private final NombaPaymentGateway nomba;

    public record BankLookupRequest(@NotBlank String accountNumber, @NotBlank String bankCode) {}
    public record BankLookupResult(boolean found, String accountNumber, String accountName, String failureReason) {}
    public record BankListEntry(String name, String bankCode) {}

    @GetMapping("/payout-banks")
    public ResponseEntity<List<BankListEntry>> listBanks() {
        List<BankListEntry> banks = nomba.listBanks().stream()
                .map(b -> new BankListEntry(b.name(), b.code()))
                .toList();
        return ResponseEntity.ok(banks);
    }

    /** Preview-only — resolves the account holder's name so the frontend can show it for confirmation before saving. Does not persist anything. */
    @PostMapping("/payout-account/lookup")
    public ResponseEntity<BankLookupResult> lookupPayoutAccount(@org.springframework.web.bind.annotation.RequestBody BankLookupRequest req) {
        NombaPaymentGateway.BankLookupResponse result = nomba.lookupBankAccount(req.accountNumber(), req.bankCode());
        return ResponseEntity.ok(new BankLookupResult(result.found(), result.accountNumber(), result.accountName(), result.failureReason()));
    }

    /**
     * Actually saves the payout destination — re-runs the lookup itself
     * server-side rather than trusting a client-supplied accountName from
     * an earlier /lookup call (which could be stale or tampered with),
     * and rejects the save outright if Nomba can't resolve the account.
     */
    @PatchMapping("/payout-account")
    public ResponseEntity<BankLookupResult> savePayoutAccount(@RequestBody BankLookupRequest req) {
        String merchantId = TenantContext.requireMerchantId();
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));

        NombaPaymentGateway.BankLookupResponse result = nomba.lookupBankAccount(req.accountNumber(), req.bankCode());
        if (!result.found()) {
            throw new BusinessRuleException("bank_account_not_found",
                    result.failureReason() != null ? result.failureReason() : "Could not verify this bank account.");
        }

        merchant.setPayoutBankAccountNumber(result.accountNumber());
        merchant.setPayoutBankCode(req.bankCode());
        merchant.setPayoutAccountName(result.accountName()); // Nomba's resolved name — never the client's
        merchantRepository.save(merchant);

        return ResponseEntity.ok(new BankLookupResult(true, result.accountNumber(), result.accountName(), null));
    }
}