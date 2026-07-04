package co.subpilot.disbursement.service;

import co.subpilot.disbursement.DisbursementStatus;
import co.subpilot.disbursement.entity.Disbursement;
import co.subpilot.disbursement.repository.DisbursementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Background counterpart to DisbursementService.reconcilePending — that
 * method fires on every GET /v1/payouts/{id}, which resolves things
 * quickly for a merchant actively checking, but nothing resolves a pending
 * disbursement for a merchant who never polls. This sweep is what makes
 * pending disbursements resolve on their own eventually either way, same
 * relationship as NombaReconciliationJob has to the inbound webhook path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementReconciliationJob {

    private final DisbursementRepository disbursementRepository;
    private final DisbursementService disbursementService;

    @Scheduled(fixedDelayString = "${subpilot.nomba.reconciliation.interval-ms:300000}")
    @SchedulerLock(name = "disbursement_reconciliation", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void reconcile() {
        List<Disbursement> pending = disbursementRepository.findByStatus(DisbursementStatus.PENDING);
        if (pending.isEmpty()) return;

        log.info("Disbursement TSQ: reconciling {} pending payout(s)", pending.size());

        for (Disbursement disbursement : pending) {
            try {
                disbursementService.reconcilePending(disbursement);
            } catch (Exception e) {
                log.error("Disbursement TSQ: failed to reconcile disbursement={}: {}",
                        disbursement.getId(), e.getMessage(), e);
            }
        }
    }
}