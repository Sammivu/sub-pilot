package co.subpilot.dunning.service;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.customer.entity.Customer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    @Autowired
    @Lazy
    private DunningTriggerService self;

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

        // ── Compute VA expiry from campaign grace period ──────────────────────
        Instant expiry = Instant.now().plus(campaign.getGracePeriodDays() + 1, ChronoUnit.DAYS);
        String expiryDate = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(expiry);
        // Create virtual account BEFORE saving the execution so we can store the details
        Customer customer = customerRepository.findById(sub.getCustomerId()).orElseThrow();
        String accountRef = "transfer_" + sub.getId(); // 35 chars — within 16–64 limit ✅

        NombaPaymentGateway.VirtualAccountResponse va = nomba.createVirtualAccount(
                new NombaPaymentGateway.VirtualAccountRequest(
                        accountRef,
                        padAccountName(customer.getFullName()), // enforce 8-char minimum
                        invoice.getAmount(), invoice.getCurrency(), expiryDate         // major units, not kobo
                )
        );

        DunningExecution.DunningExecutionBuilder builder = DunningExecution.builder()
                .merchantId(sub.getMerchantId())
                .subscriptionId(sub.getId())
                .invoiceId(invoice.getId())
                .campaignId(campaign.getId())
                .currentStep(0)
                .status("active");

        if (va.success()) {
            builder.vaBankName(va.bankName())
                    .vaAccountNumber(va.bankAccountNumber())
                    .vaAccountName(va.bankAccountName())
                    .vaAccountRef(va.accountReference());
            log.info("Virtual account created for dunning — subscription={} accountNumber={}",
                    sub.getId(), va.bankAccountNumber());
        } else {
            log.warn("Virtual account creation failed for dunning — subscription={} reason={}",
                    sub.getId(), va.errorMessage());
        }

        DunningExecution execution = executionRepository.save(builder.build());
        eventService.emit(sub.getMerchantId(), "dunning.started", "subscription",
                sub.getId(), Map.of("executionId", execution.getId(), "invoiceId", invoice.getId()));

        // Notify both the subscriber (payment failed, with self-cure link)
        // and the merchant (PRD §6.9) right away, on the very first failure
        // — not just at later dunning steps.
        notificationService.sendPaymentFailed(sub, invoice, latestFailureReason(invoice), execution);
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
                self.processExecution(execution);
            } catch (Exception e) {
                log.error("Dunning error for execution {}: {}", execution.getId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void processExecution(DunningExecution execution) {

        DunningExecution execute = executionRepository.findByIdForUpdate(execution.getId())
                .orElseThrow(() -> new RuntimeException("Dunning execution not found: " + execution.getId()));

        // Another thread may already have resolved it while we were waiting
        if (!"active".equals(execute.getStatus())) {
            return;
        }
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

        // DB idempotency key: still per-invoice (success is terminal regardless of step)
        String dbIdempotencyKey = "invoice:" + invoice.getId();

        // Short-circuit if a prior step already succeeded for this invoice
        var existingSucceeded = paymentAttemptRepository.findByIdempotencyKey(dbIdempotencyKey)
                .filter(a -> "succeeded".equals(a.getStatus()));
        if (existingSucceeded.isPresent()) {
            return true;
        }

        // Nomba orderReference must be unique PER ATTEMPT to avoid the
        // "An order already exists with this order reference" 400 on retries.
        // We scope it to the invoice + dunning step so re-runs of the same step
        // (scheduler crash/restart) are still idempotent at Nomba's level too.
        String nombaOrderReference = "invoice:" + invoice.getId() + ":step:" + stepNumber;

        // Also guard against inserting a duplicate failed attempt for this step
        // if the scheduler runs twice before currentStep is persisted
        String stepIdempotencyKey = nombaOrderReference;
        var existingAttempt = paymentAttemptRepository.findByIdempotencyKey(stepIdempotencyKey);
        if (existingAttempt.isPresent() && existingAttempt.get().isTerminal()) {
            return "succeeded".equals(existingAttempt.get().getStatus());
        }

        Customer customer = customerRepository.findByIdAndMerchantId(sub.getCustomerId(), sub.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("customer", sub.getCustomerId()));

        NombaPaymentGateway.ChargeResponse charge = nomba.chargeToken(
                new NombaPaymentGateway.ChargeRequest(
                        cardToken, nombaOrderReference, invoice.getAmount(),
                        invoice.getCurrency(), customer.getEmail(),
                        sub.getId(), customer.getNombaCustomerId(), invoice.getId()
                )
        );

        PaymentAttempt newAttempt = new PaymentAttempt();
        newAttempt.setId(com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString());
        newAttempt.setMerchantId(sub.getMerchantId());
        newAttempt.setInvoiceId(invoice.getId());
        newAttempt.setSubscriptionId(sub.getId());
        newAttempt.setIdempotencyKey(stepIdempotencyKey); // scoped to step, not just invoice
        newAttempt.setAmount(invoice.getAmount());
        newAttempt.setCurrency(invoice.getCurrency());
        newAttempt.setStatus(charge.success() ? "succeeded" : "failed");
        newAttempt.setNombaReference(charge.reference());
        newAttempt.setFailureCode(charge.failureCode());
        newAttempt.setFailureReason(charge.failureReason());
        newAttempt.setAttemptedAt(Instant.now());
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
        if (!invoice.isPaid()) {   // ← guard so callers that already markPaid don't double-call
            invoiceService.markPaid(invoice.getId(), "dunning_recovery");
        }

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

            notificationService.sendDunningWarning(sub, daysRemaining, execution);
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
    public void resolveViaSelfCure(String subscriptionToken, String newCardToken, String nombaReference, String nombaCustomerId) {
        Subscription sub = subscriptionRepository.findBySubscriptionToken(subscriptionToken)
                .orElseThrow(() -> new RuntimeException("Invalid subscription token"));

        sub.setNombaCardTokenRef(newCardToken);
        sub.setNombaCustomerRef(nombaCustomerId);
        sub.setPendingCardUpdateAt(null);
        subscriptionRepository.save(sub);

        customerRepository.findById(sub.getCustomerId()).ifPresent(c -> {
            c.setCardToken(newCardToken);
            c.setNombaCustomerId(nombaCustomerId);
            customerRepository.save(c);
        });

        executionRepository.findBySubscriptionIdAndStatus(sub.getId(), "active")
                .ifPresent(execution -> {
                            DunningExecution lockedExecution =
                                    executionRepository.findByIdForUpdate(execution.getId())
                                            .orElseThrow(() -> new RuntimeException("Dunning execution disappeared: " + execution.getId()));
                            if (!"active".equals(lockedExecution.getStatus())) {
                                return;
                            }
                            invoiceRepository.findById(lockedExecution.getInvoiceId())
                                    .ifPresent(invoice -> {
                                                if (invoice.isPaid()) {
                                                    return;
                                                }
                                                // The card-update checkout itself already charged
                                                // the customer (see PortalController.updateCard —
                                                // it now charges the specific outstanding invoice
                                                // amount). nombaReference is that checkout's real
                                                // Nomba transaction id. Previously this called
                                                // attemptCharge() here, firing a SEPARATE,
                                                // independent charge via chargeToken() with its
                                                // own orderReference — completely unrelated to
                                                // the payment the customer just made in their
                                                // browser. If that second charge failed for any
                                                // reason, nothing ever resolved, even though the
                                                // customer had already paid. Record the
                                                // checkout's own payment directly instead.
                                                recordSelfCurePayment(sub, invoice, nombaReference);
                                                invoiceService.markPaid(invoice.getId(), nombaReference);
                                                resolveDunning(lockedExecution, sub, invoice);
                                            }
                                    );
                        }
                );
    }

    /**
     * Records a PaymentAttempt reflecting the card-update checkout's own
     * payment — same audit-trail purpose attemptCharge() serves for a
     * server-initiated charge, just sourced from the checkout instead of a
     * second chargeToken() call. Same idempotencyKey convention ("invoice:"
     * + invoiceId) as attemptCharge uses, so if webhook and TSQ both resolve
     * this (a real possible race), the second call is a safe no-op.
     */
    private void recordSelfCurePayment(Subscription sub, Invoice invoice, String nombaReference) {
        String idempotencyKey = "invoice:" + invoice.getId();

        // Re-use existing attempt record if present (upsert pattern),
        // rather than always inserting — avoids unique constraint violation
        // when a prior dunning retry already recorded a failed attempt with
        // this key.
        PaymentAttempt attempt = paymentAttemptRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseGet(PaymentAttempt::new);

        if ("succeeded".equals(attempt.getStatus())) {
            return; // already recorded as success, nothing to do
        }

        if (attempt.getId() == null) {
            attempt.setId(com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString());
            attempt.setMerchantId(sub.getMerchantId());
            attempt.setInvoiceId(invoice.getId());
            attempt.setSubscriptionId(sub.getId());
            attempt.setIdempotencyKey(idempotencyKey);
            attempt.setAmount(invoice.getAmount());
            attempt.setCurrency(invoice.getCurrency());
        }

        attempt.setStatus("succeeded");
        attempt.setNombaReference(nombaReference);
        attempt.setResolvedAt(Instant.now());
        paymentAttemptRepository.save(attempt);  // UPDATE if existing, INSERT if new
    }

    private String padAccountName(String name) {
        return name != null && name.length() >= 8 ? name : String.format("%-8s", name).replace(' ', '_');
    }

    @Transactional
    public void resolveViaBankTransfer(String subscriptionId, String nombaReference) {
        executionRepository.findBySubscriptionIdAndStatus(subscriptionId, "active")
                .ifPresent(execution -> {
                    DunningExecution locked = executionRepository.findByIdForUpdate(execution.getId())
                            .orElseThrow();
                    if (!"active".equals(locked.getStatus())) return;

                    invoiceRepository.findById(locked.getInvoiceId()).ifPresent(invoice -> {
                        if (invoice.isPaid()) return;

                        recordSelfCurePayment(  // reuse the existing private method
                                subscriptionRepository.findById(subscriptionId).orElseThrow(),
                                invoice, nombaReference);
                        invoiceService.markPaid(invoice.getId(), nombaReference);
                        resolveDunning(locked,
                                subscriptionRepository.findById(subscriptionId).orElseThrow(),
                                invoice);
                    });
                });
    }
}