package co.subpilot.disbursement.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.disbursement.DisbursementStatus;
import co.subpilot.disbursement.entity.Disbursement;
import co.subpilot.disbursement.repository.DisbursementRepository;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.fee.repository.PlatformFeeRepository;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.nomba.NombaPaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Backend Gap checklist item — DisbursementService + DisbursementController.
 *
 * SubPilot's checkout always creates orders against ONE central Nomba
 * accountId (see NombaGatewayImpl.initiateCheckout — no splitRequest is
 * used), so every customer payment for every merchant lands in a single
 * pooled wallet. PlatformFeeService already ledgers what each merchant is
 * OWED (PlatformFee.netAmount per invoice) the moment a charge succeeds,
 * but that's just a number in the database — nothing physically moves
 * until this service runs. This is the piece that actually separates a
 * merchant's share out of the pool into their own Nomba account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementService {

    private final DisbursementRepository disbursementRepository;
    private final PlatformFeeRepository platformFeeRepository;
    private final MerchantRepository merchantRepository;
    private final NombaPaymentGateway nomba;
    private final EventService eventService;

    @Transactional
    public Disbursement trigger(String triggeredByUserId) {
        String merchantId = TenantContext.requireMerchantId();

        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("merchant", merchantId));

        if (merchant.getPayoutBankAccountNumber() == null || merchant.getPayoutBankAccountNumber().isBlank() ||
                merchant.getPayoutBankCode() == null || merchant.getPayoutBankCode().isBlank()) {
            throw new BusinessRuleException("payout_account_not_configured",
                    "This merchant has no payout bank account configured yet — nothing to transfer to.");
        }

        // Duplicate-payout guard, per Nomba's own documented guidance: never
        // create a second merchantTxRef while one is still pending. If one
        // is outstanding, the caller needs to resolve it (poll GET
        // /v1/payouts/{id}, which triggers a requery — see get()) before a
        // new payout can be triggered.
        disbursementRepository.findByMerchantIdAndStatus(merchantId, DisbursementStatus.PENDING)
                .ifPresent(pending -> {
                    throw new BusinessRuleException("payout_already_pending",
                            "Disbursement " + pending.getId() + " is still pending confirmation from Nomba — " +
                                    "check its status before triggering another payout.");
                });

        // The idempotent cursor: pick up exactly where the last SUCCESSFUL
        // payout left off. A merchant's very first payout has no prior
        // cursor, so it covers everything ever ledgered for them.
        Instant periodStart = disbursementRepository.findLastSuccessful(merchantId)
                .map(Disbursement::getPeriodEnd)
                .orElse(null);
        Instant periodEnd = Instant.now();
        Instant effectiveSince = periodStart != null ? periodStart : Instant.EPOCH;

        long amount = platformFeeRepository.sumNetByMerchantBetween(merchantId, effectiveSince, periodEnd);
        long invoiceCount = platformFeeRepository.countByMerchantBetween(merchantId, effectiveSince, periodEnd);

        if (amount <= 0) {
            throw new BusinessRuleException("nothing_to_disburse",
                    "No net proceeds are owed to this merchant since the last payout.");
        }

        Disbursement disbursement = disbursementRepository.save(Disbursement.builder()
                .merchantId(merchantId)
                .amount(amount)
                .currency("NGN")
                .status(DisbursementStatus.PENDING)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .invoiceCount((int) invoiceCount)
                .triggeredByUserId(triggeredByUserId)
                .build());

        eventService.record(merchantId, EventType.PAYOUT_TRIGGERED, "disbursement", disbursement.getId(),
                Map.of("amount", amount, "invoiceCount", invoiceCount));

        // Synchronous, same reasoning as RefundService — a merchant
        // triggering "pay me out now" wants to know immediately whether it
        // worked, not poll a background job. idempotencyKey = disbursement.id,
        // used as Nomba's merchantTxRef — this value must never be reused
        // for a different transfer attempt (Nomba's own duplicate-payout
        // guidance), which is exactly why the pending-guard above exists:
        // it's the mechanism that stops a second Disbursement (and thus a
        // second merchantTxRef) ever being created while this one is
        // unresolved.
        NombaPaymentGateway.BankTransferRequest request = new NombaPaymentGateway.BankTransferRequest(
                merchant.getPayoutBankAccountNumber(), merchant.getPayoutAccountName(), merchant.getPayoutBankCode(),
                amount, "NGN", disbursement.getId(),
                "SubPilot payout — " + invoiceCount + " invoice(s)");
        NombaPaymentGateway.TransferResponse response = nomba.initiateBankTransfer(request);

        if (response.success()) {
            disbursement.setStatus(DisbursementStatus.SUCCEEDED);
            disbursement.setNombaTransferReference(response.reference());
            disbursement.setResolvedAt(Instant.now());
            disbursementRepository.save(disbursement);

            eventService.record(merchantId, EventType.PAYOUT_SUCCEEDED, "disbursement", disbursement.getId(),
                    Map.of("amount", amount, "nombaTransferReference", response.reference()));

            log.info("Payout succeeded: merchant={} disbursement={} amount={} invoices={}",
                    merchantId, disbursement.getId(), amount, invoiceCount);
        } else if (response.isPending()) {
            // Deliberately left in PENDING status, not FAILED — per Nomba's
            // docs this transaction "may still be processed successfully."
            // The pending-guard at the top of this method is what prevents
            // a second trigger from racing this one; DisbursementReconciliationJob
            // (or a manual GET /v1/payouts/{id}) is what resolves it later.
            disbursement.setNombaTransferReference(response.reference());
            disbursementRepository.save(disbursement);

            log.info("Payout pending confirmation: merchant={} disbursement={} status={}",
                    merchantId, disbursement.getId(), response.status());
        } else {
            // Covers both a genuine failure AND response.isRefunded()
            // (Nomba couldn't deliver and auto-reversed). Both are terminal
            // and — unlike PENDING — Nomba's docs explicitly say a REFUND
            // outcome is safe to retry with a brand-new merchantTxRef; that
            // retry is just "trigger payout again", which the normal
            // pending-guard flow already allows once this one is FAILED,
            // so no special-casing is needed here beyond noting it in the
            // failureReason for visibility.
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(response.isRefunded()
                    ? "Transfer was auto-reversed by Nomba (status=REFUND) — safe to retry"
                    : response.failureReason());
            disbursement.setResolvedAt(Instant.now());
            disbursementRepository.save(disbursement);

            eventService.record(merchantId, EventType.PAYOUT_FAILED, "disbursement", disbursement.getId(),
                    Map.of("amount", amount, "failureReason",
                            response.failureReason() != null ? response.failureReason() : "unknown"));

            log.warn("Payout failed: merchant={} disbursement={} reason={}",
                    merchantId, disbursement.getId(), response.failureReason());
        }

        return disbursement;
    }

    /**
     * Called from GET /v1/payouts/{id} whenever a disbursement is still
     * PENDING — requeries Nomba directly via the same merchantTxRef rather
     * than ever minting a new one. Idempotent: a disbursement already
     * terminal is returned unchanged, so this is safe to call on every
     * single GET without worrying about redundant Nomba calls mattering —
     * though in practice you'd want to skip the requery call itself once
     * terminal, which the caller (get()) already does.
     */
    @Transactional
    public Disbursement reconcilePending(Disbursement disbursement) {
        if (disbursement.isTerminal()) return disbursement;

        NombaPaymentGateway.TransferResponse response = nomba.verifyTransfer(disbursement.getId());

        if (response.success()) {
            disbursement.setStatus(DisbursementStatus.SUCCEEDED);
            disbursement.setNombaTransferReference(response.reference());
            disbursement.setResolvedAt(Instant.now());
            disbursementRepository.save(disbursement);

            eventService.record(disbursement.getMerchantId(), EventType.PAYOUT_SUCCEEDED, "disbursement", disbursement.getId(),
                    Map.of("amount", disbursement.getAmount(), "nombaTransferReference", response.reference()));
        } else if (!response.isPending()) {
            disbursement.setStatus(DisbursementStatus.FAILED);
            disbursement.setFailureReason(response.failureReason());
            disbursement.setResolvedAt(Instant.now());
            disbursementRepository.save(disbursement);

            eventService.record(disbursement.getMerchantId(), EventType.PAYOUT_FAILED, "disbursement", disbursement.getId(),
                    Map.of("amount", disbursement.getAmount(), "failureReason",
                            response.failureReason() != null ? response.failureReason() : "unknown"));
        }
        // else: still pending — leave as-is, nothing to update

        return disbursement;
    }

    public Page<Disbursement> list(Pageable pageable) {
        String merchantId = TenantContext.requireMerchantId();
        return disbursementRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable);
    }

    public Disbursement get(String disbursementId) {
        String merchantId = TenantContext.requireMerchantId();
        Disbursement disbursement = disbursementRepository.findByIdAndMerchantId(disbursementId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("disbursement", disbursementId));

        if (!disbursement.isTerminal()) {
            disbursement = reconcilePending(disbursement);
        }
        return disbursement;
    }
}