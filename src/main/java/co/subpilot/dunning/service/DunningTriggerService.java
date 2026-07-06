package co.subpilot.dunning.service;

import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.dunning.entity.DunningCampaign;
import co.subpilot.dunning.entity.DunningExecution;
import co.subpilot.dunning.entity.DunningStep;
import co.subpilot.dunning.repository.DunningCampaignRepository;
import co.subpilot.dunning.repository.DunningExecutionRepository;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.notification.service.NotificationService;
import co.subpilot.payment.entity.PaymentAttempt;
import co.subpilot.payment.repository.PaymentAttemptRepository;
import co.subpilot.subscription.SubscriptionStateMachine;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DunningTriggerService: starts a dunning execution when billing fails.
 * DunningSchedulerJob: processes pending dunning steps every hour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DunningTriggerService {

    private final DunningCampaignRepository campaignRepository;
    private final DunningExecutionRepository executionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final EventService eventService;
    private final NombaPaymentGateway nomba;
    private final NotificationService notificationService;

    @Value("${subpilot.frontend.base-url}")
    private String frontendBaseUrl;

    // ── Start dunning after billing failure ───────────────────────────────────

    @Transactional
    public void startDunning(Subscription sub, Invoice invoice) {
        // Don't start if already active
        if (executionRepository.findBySubscriptionIdAndStatus(sub.getId(), "active").isPresent()) {
            log.debug("Dunning already active for subscription {}", sub.getId());
            return;
        }

        DunningCampaign campaign = campaignRepository
                .findByMerchantIdAndIsDefaultTrue(sub.getMerchantId())
                .orElseGet(() -> createDefaultCampaign(sub.getMerchantId()));

        DunningExecution execution = executionRepository.save(DunningExecution.builder()
                .merchantId(sub.getMerchantId())
                .subscriptionId(sub.getId())
                .invoiceId(invoice.getId())
                .campaignId(campaign.getId())
                .currentStep(0)
                .status("active")
                .build());

        eventService.emit(sub.getMerchantId(), "dunning.started", "subscription",
                sub.getId(), Map.of("executionId", execution.getId(), "invoiceId", invoice.getId()));

        // Notify both the subscriber (payment failed, with self-cure link)
        // and the merchant (PRD §6.9) right away, on the very first failure
        // — not just at later dunning steps.
        notificationService.sendPaymentFailed(sub, invoice, latestFailureReason(invoice));
        notificationService.sendPaymentFailedMerchantAlert(sub, invoice, latestFailureReason(invoice));

        log.info("Dunning started: subscription={} execution={}", sub.getId(), execution.getId());
    }

    // ── Dunning scheduler — runs hourly ───────────────────────────────────────

    @Scheduled(fixedDelayString = "3600000") // 1 hour
    @SchedulerLock(name = "dunning_scheduler", lockAtLeastFor = "PT50M", lockAtMostFor = "PT2H")
    public void processDunningSteps() {
        List<DunningExecution> active = executionRepository.findByStatusOrderByStartedAtAsc("active");
        if (active.isEmpty()) return;

        log.info("Dunning scheduler: processing {} active executions", active.size());

        for (DunningExecution execution : active) {
            try {
                processExecution(execution);
            } catch (Exception e) {
                log.error("Dunning error for execution {}: {}", execution.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void processExecution(DunningExecution execution) {
        DunningCampaign campaign = campaignRepository.findById(execution.getCampaignId())
                .orElseThrow(() -> new RuntimeException("Campaign not found: " + execution.getCampaignId()));

        List<DunningStep> steps = campaign.getSteps();
        Instant failureStart = execution.getStartedAt();
        boolean gracePeriodElapsed = Instant.now().isAfter(failureStart.plus(campaign.getGracePeriodDays(), ChronoUnit.DAYS));

        // Grace period elapsing is checked independently of step exhaustion —
        // a subscription moves to 'suspended' the moment grace period ends,
        // even if dunning steps are still scheduled to run afterwards. This
        // is the explicit, webhook-visible signal (subscription.suspended)
        // that downstream product teams should cut off access — see V8
        // migration / SubscriptionStateMachine for the full rationale.
        //
        // We still keep retrying/emailing on schedule after suspension (a
        // suspended subscription is NOT dunning-exhausted — it only becomes
        // exhausted once every step has run AND grace period has passed),
        // so self-cure and scheduled retries both continue to work exactly
        // as before; only the subscription's reported status changes.
        if (gracePeriodElapsed) {
            suspendIfNotAlready(execution);
        }

        // Find the next step due to execute
        for (DunningStep step : steps) {
            if (step.getStepNumber() <= execution.getCurrentStep()) continue; // already executed

            Instant scheduledAt = failureStart.plus(step.getDayOffset(), ChronoUnit.DAYS);
            if (Instant.now().isBefore(scheduledAt)) continue; // not yet due

            executeStep(execution, step, campaign);
            return;
        }

        // All steps executed AND grace period has passed — now truly exhausted.
        if (gracePeriodElapsed) {
            exhaustDunning(execution, campaign);
        }
    }

    /**
     * Transitions the subscription to 'suspended' the first time grace
     * period elapses for this execution. Idempotent — safe to call on every
     * scheduler pass for the remaining lifetime of the execution, since it
     * no-ops once the subscription is already past 'past_due' in the state
     * machine (e.g. already suspended, or already resolved/cancelled by a
     * concurrent self-cure).
     */
    private void suspendIfNotAlready(DunningExecution execution) {
        subscriptionRepository.findById(execution.getSubscriptionId()).ifPresent(sub -> {
            if (sub.getStatus() != SubscriptionStatus.past_due) {
                return; // already suspended, or moved on (recovered/cancelled) since this check last ran
            }
            SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.suspended);
            sub.setStatus(SubscriptionStatus.suspended);
            subscriptionRepository.save(sub);

            eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_SUSPENDED, "subscription",
                    sub.getId(), Map.of("executionId", execution.getId()));

            notificationService.sendSubscriptionSuspended(sub);

            log.info("Subscription suspended (grace period elapsed): subscription={} execution={}",
                    sub.getId(), execution.getId());
        });
    }

    private void executeStep(DunningExecution execution, DunningStep step, DunningCampaign campaign) {
        Subscription sub = subscriptionRepository.findById(execution.getSubscriptionId())
                .orElseThrow(() -> new RuntimeException("Subscription not found"));
        Invoice invoice = invoiceRepository.findById(execution.getInvoiceId())
                .orElseThrow(() -> new RuntimeException("Invoice not found"));

        boolean chargeSucceeded = false;

        // ── Retry charge ───────────────────────────────────────────────────
        if ("retry_charge".equals(step.getAction()) || "both".equals(step.getAction())) {
            chargeSucceeded = attemptCharge(sub, invoice, step.getStepNumber());
        }

        // ── Send email ─────────────────────────────────────────────────────
        if (("send_email".equals(step.getAction()) || "both".equals(step.getAction())) && !chargeSucceeded) {
            sendDunningEmail(sub, invoice, step.getEmailTemplate(), execution, campaign);
        }

        eventService.emit(sub.getMerchantId(), "dunning.step_executed", "subscription",
                sub.getId(), Map.of("step", step.getStepNumber(), "action", step.getAction()));

        if (chargeSucceeded) {
            resolveDunning(execution, sub, invoice);
        } else {
            execution.setCurrentStep(step.getStepNumber());
            executionRepository.save(execution);
        }
    }

    private boolean attemptCharge(Subscription sub, Invoice invoice, int stepNumber) {
        String cardToken = sub.getNombaCardTokenRef();
        if (cardToken == null) return false;

        String idempotencyKey = sub.getId() + ":dunning:" + stepNumber + ":" + invoice.getId();
        // Check idempotency
        var existingSucceeded = paymentAttemptRepository.findByIdempotencyKey(idempotencyKey)
                .filter(a -> "succeeded".equals(a.getStatus()));
        if (existingSucceeded.isPresent()) {
            return true; // already succeeded
        }

        NombaPaymentGateway.ChargeResponse charge = nomba.chargeToken(
                new NombaPaymentGateway.ChargeRequest(
                        cardToken, idempotencyKey, invoice.getAmount(),
                        invoice.getCurrency(), sub.getMerchantId(),
                        sub.getId(), invoice.getId()
                )
        );

        PaymentAttempt newAttempt = new PaymentAttempt();
        newAttempt.setId(com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString());
        newAttempt.setMerchantId(sub.getMerchantId());
        newAttempt.setInvoiceId(invoice.getId());
        newAttempt.setSubscriptionId(sub.getId());
        newAttempt.setIdempotencyKey(idempotencyKey);
        newAttempt.setAmount(invoice.getAmount());
        newAttempt.setCurrency(invoice.getCurrency());
        newAttempt.setStatus(charge.success() ? "succeeded" : "failed");
        newAttempt.setNombaReference(charge.reference());
        newAttempt.setFailureCode(charge.failureCode());
        newAttempt.setFailureReason(charge.failureReason());
        newAttempt.setResolvedAt(Instant.now());
        paymentAttemptRepository.save(newAttempt);

        return charge.success();
    }

    private void resolveDunning(DunningExecution execution, Subscription sub, Invoice invoice) {
        execution.setStatus("resolved");
        execution.setResolvedAt(Instant.now());
        executionRepository.save(execution);

        SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.active);
        sub.setStatus(SubscriptionStatus.active);
        subscriptionRepository.save(sub);
        invoiceService.markPaid(invoice.getId(), "dunning_recovery");

        eventService.emit(sub.getMerchantId(), "dunning.recovered", "subscription",
                sub.getId(), Map.of("executionId", execution.getId()));

        log.info("Dunning resolved: subscription={} execution={}", sub.getId(), execution.getId());
    }

    private void exhaustDunning(DunningExecution execution, DunningCampaign campaign) {
        execution.setStatus("exhausted");
        execution.setResolvedAt(Instant.now());
        executionRepository.save(execution);

        subscriptionRepository.findById(execution.getSubscriptionId()).ifPresent(sub -> {
            SubscriptionStatus target = Boolean.TRUE.equals(campaign.getCancelAfterExhaustion())
                    ? SubscriptionStatus.cancelled
                    : SubscriptionStatus.expired;

            SubscriptionStateMachine.assertCanTransition(sub.getStatus(), target);
            sub.setStatus(target);
            if (target == SubscriptionStatus.cancelled) {
                sub.setCancelledAt(Instant.now());
                sub.setCancellationReason("dunning_exhausted");
            }
            subscriptionRepository.save(sub);

            eventService.emit(sub.getMerchantId(), "dunning.exhausted", "subscription",
                    sub.getId(), Map.of("executionId", execution.getId(), "outcome", target.name()));

            // PRD §6.9: merchant alert when dunning fully exhausts without recovery.
            notificationService.sendDunningExhaustedMerchantAlert(sub);
        });

        log.warn("Dunning exhausted: execution={}", execution.getId());
    }

    /**
     * Sends the subscriber-facing dunning email for a given step's template.
     * Days-remaining is computed from the campaign's grace period so the
     * DUNNING_WARNING template can say "cancelled in N days" accurately
     * regardless of which step in the sequence this is.
     */
    private void sendDunningEmail(Subscription sub, Invoice invoice, String template, DunningExecution execution, DunningCampaign campaign) {
        try {
            long daysElapsed = ChronoUnit.DAYS.between(execution.getStartedAt(), Instant.now());
            int daysRemaining = (int) Math.max(0, campaign.getGracePeriodDays() - daysElapsed);

            notificationService.sendDunningWarning(sub, daysRemaining);
            log.info("Dunning email sent: template={} subscription={} daysRemaining={}",
                    template, sub.getId(), daysRemaining);
        } catch (Exception e) {
            log.error("Failed to send dunning email for subscription {}: {}", sub.getId(), e.getMessage(), e);
        }
    }

    /** Most recent failure reason recorded against an invoice's payment attempts, if any. */
    private String latestFailureReason(Invoice invoice) {
        return paymentAttemptRepository.findByInvoiceIdOrderByAttemptedAtDesc(invoice.getId())
                .stream()
                .filter(a -> "failed".equals(a.getStatus()))
                .findFirst()
                .map(PaymentAttempt::getFailureReason)
                .orElse(null);
    }

    /**
     * Gap 3 fix: previously only created lazily on a merchant's first
     * payment failure (via the orElseGet below), which meant GET
     * /v1/dunning/campaigns returned an empty list for any merchant who
     * hadn't churned yet — they had no way to configure dunning proactively.
     * Now called eagerly from AuthService.signup() as well, so every
     * merchant has a campaign to view/edit from day one. Safe to call more
     * than once for the same merchant: guarded by findByMerchantIdAndIsDefaultTrue.
     */
    @Transactional
    public DunningCampaign createDefaultCampaign(String merchantId) {
        return campaignRepository.findByMerchantIdAndIsDefaultTrue(merchantId)
                .orElseGet(() -> buildAndSaveDefaultCampaign(merchantId));
    }

    private DunningCampaign buildAndSaveDefaultCampaign(String merchantId) {
        DunningCampaign campaign = DunningCampaign.builder()
                .merchantId(merchantId)
                .name("Default Campaign")
                .gracePeriodDays(21)
                .maxAttempts(4)
                .isDefault(true)
                .cancelAfterExhaustion(true)
                .build();

        campaign = campaignRepository.save(campaign);

        // Add default 5-step sequence
        String campaignId = campaign.getId();
        List<DunningStep> steps = List.of(
                buildStep(campaignId, 1, 0,  "both",         "payment_failed"),
                buildStep(campaignId, 2, 3,  "retry_charge", null),
                buildStep(campaignId, 3, 7,  "both",         "final_warning"),
                buildStep(campaignId, 4, 14, "retry_charge", null),
                buildStep(campaignId, 5, 21, "both",         "service_suspended")
        );

        campaign.getSteps().addAll(steps);
        return campaignRepository.save(campaign);
    }

    private DunningStep buildStep(String campaignId, int num, int dayOffset, String action, String template) {
        return DunningStep.builder()
                .campaignId(campaignId)
                .stepNumber(num)
                .dayOffset(dayOffset)
                .action(action)
                .emailTemplate(template)
                .build();
    }

    // ── Self-cure: subscriber updates payment method via portal ──────────────

    @Transactional
    public void resolveViaSelfCure(String subscriptionToken, String newCardToken, String nombaCustomerId) {
        Subscription sub = subscriptionRepository.findBySubscriptionToken(subscriptionToken)
                .orElseThrow(() -> new RuntimeException("Invalid subscription token"));

        // Update card token
        sub.setNombaCardTokenRef(newCardToken);
        sub.setNombaCustomerRef(nombaCustomerId);
        sub.setPendingCardUpdateAt(null); // resolved — TSQ no longer needs to chase this one
        subscriptionRepository.save(sub);

        // Keep the customer's record in sync too — mirrors activateAfterCheckout,
        // so the customer's most recently used card is reflected account-wide,
        // not just on this one subscription.
        customerRepository.findById(sub.getCustomerId()).ifPresent(c -> {
            c.setCardToken(newCardToken);
            c.setNombaCustomerId(nombaCustomerId);
            customerRepository.save(c);
        });

        // Find active dunning execution and attempt charge
        executionRepository.findBySubscriptionIdAndStatus(sub.getId(), "active")
                .ifPresent(execution -> {
                    invoiceRepository.findById(execution.getInvoiceId()).ifPresent(invoice -> {
                        boolean charged = attemptCharge(sub, invoice, 99); // step 99 = self-cure
                        if (charged) {
                            resolveDunning(execution, sub, invoice);
                        }
                    });
                });
    }
}