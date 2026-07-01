package co.subpilot.subscription.service;

import co.subpilot.common.exception.BusinessRuleException;
import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.common.tenant.TenantContext;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.event.EventType;
import co.subpilot.event.service.EventService;
import co.subpilot.invoice.service.InvoiceService;
import co.subpilot.nomba.CheckoutPurpose;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.notification.service.NotificationService;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import co.subpilot.subscription.BillingPeriodCalculator;
import co.subpilot.subscription.SubscriptionStateMachine;
import co.subpilot.subscription.dto.SubscriptionDtos;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.enums.SubscriptionStatus;
import co.subpilot.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final EventService eventService;
    private final NombaPaymentGateway nomba;
    private final NotificationService notificationService;

    @Value("${subpilot.frontend.base-url}")
    private String frontendBaseUrl;

    // ── Public checkout: create subscription from plan page (PRD §6.4) ────────

    /**
     * Initiates the checkout flow for a new subscriber.
     * Returns a Nomba checkout URL to redirect the subscriber to.
     */
    @Transactional
    public SubscriptionDtos.CheckoutInitResponse initiateCheckout(
            String merchantId, String planId,
            SubscriptionDtos.CheckoutRequest req) {

        Plan plan = planRepository.findByIdAndMerchantId(planId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("plan", planId));
        if (!plan.isPublished()) {
            throw new BusinessRuleException("plan_not_published", "This plan is not available for subscription.");
        }

        Customer customer = customerRepository.findByMerchantIdAndEmail(merchantId, req.email())
                .orElseGet(() -> customerRepository.save(Customer.builder()
                        .merchantId(merchantId)
                        .fullName(req.fullName())
                        .email(req.email())
                        .phone(req.phone())
                        .build()));

        Instant now = Instant.now();
        Instant periodEnd = BillingPeriodCalculator.addInterval(now, plan);
        boolean hasTrial = plan.getTrialDays() > 0;
        Instant trialEndsAt = hasTrial ? now.plus(plan.getTrialDays(), ChronoUnit.DAYS) : null;

        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .merchantId(merchantId)
                .customerId(customer.getId())
                .planId(planId)
                .status(hasTrial ? SubscriptionStatus.trialing : SubscriptionStatus.active)
                .currentPeriodStart(now)
                .currentPeriodEnd(periodEnd)
                .nextBillingDate(hasTrial ? trialEndsAt : periodEnd)
                .trialEndsAt(trialEndsAt)
                .build());

        String callbackUrl = frontendBaseUrl + "/plans/" + req.merchantSlug() + "/" + req.planSlug() + "/success?ref=";

        NombaPaymentGateway.CheckoutResponse checkout = nomba.initiateCheckout(
                new NombaPaymentGateway.CheckoutRequest(
                        CheckoutPurpose.NEW_SUBSCRIPTION_PREFIX + subscription.getId(),
                        plan.getAmount(),
                        plan.getCurrency(),
                        customer.getEmail(),
                        customer.getFullName(),
                        customer.getPhone(),
                        callbackUrl + subscription.getId(),
                        "Initial subscription checkout for " + subscription.getId()
                )
        );

        eventService.emit(merchantId, EventType.SUBSCRIPTION_CREATED, "subscription",
                subscription.getId(), Map.of("planId", planId, "customerId", customer.getId()));

        return new SubscriptionDtos.CheckoutInitResponse(
                subscription.getId(), checkout.checkoutUrl(), checkout.reference());
    }

    /**
     * Called after Nomba checkout completes (via Nomba webhook or callback).
     * Activates the subscription and stores the card token.
     */
    @Transactional
    public void activateAfterCheckout(String subscriptionId, String cardToken,
                                      String nombaReference, String nombaCustomerId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("subscription", subscriptionId));

        sub.setNombaCardTokenRef(cardToken);
        sub.setNombaCustomerRef(nombaCustomerId);

        // trialing -> active is a valid transition; active -> active is a no-op allowed by the guard.
        SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.active);
        sub.setStatus(SubscriptionStatus.active);
        subscriptionRepository.save(sub);

        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("plan", sub.getPlanId()));

        var invoice = invoiceService.createOrFind(sub.getMerchantId(), sub.getId(),
                sub.getCustomerId(), plan.getAmount(),
                sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd());
        invoiceService.markPaid(invoice.getId(), nombaReference);

        customerRepository.findById(sub.getCustomerId()).ifPresent(c -> {
            c.setCardToken(cardToken);
            c.setNombaCustomerId(nombaCustomerId);
            customerRepository.save(c);
        });

        eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_ACTIVATED, "subscription",
                sub.getId(), Map.of("planId", sub.getPlanId()));

        // PRD §6.9: subscriber gets "subscription activated", merchant gets
        // "new subscriber acquired" — both fire from the same activation event.
        notificationService.sendSubscriptionActivated(sub);
        notificationService.sendNewSubscriberAlert(sub);

        log.info("Subscription activated: {} via checkout", subscriptionId);
    }

    // ── State machine actions (operator console + portal) ──────────────────────

    @Transactional
    public Subscription cancel(String subscriptionId, String reason, boolean immediate) {
        String merchantId = TenantContext.requireMerchantId();
        Subscription sub = requireSubscription(subscriptionId, merchantId);
        return cancelResolved(sub, reason, immediate);
    }

    /**
     * Portal variant — the subscriber has already been authenticated by
     * possession of their opaque subscription_token (see getByToken), so no
     * merchant JWT/TenantContext is involved. The resolved Subscription is
     * passed in directly rather than re-resolving by (id, merchantId), since
     * the portal controller already has it from getByToken().
     */
    @Transactional
    public Subscription cancelViaPortal(Subscription sub, String reason) {
        // Portal-initiated cancellations are always "at period end" — PRD
        // §6.7 describes a self-service cancel, not an immediate forced
        // termination. Immediate cancellation (cutting off access right
        // away) stays an operator-only action via the console endpoint.
        return cancelResolved(sub, reason, false);
    }

    private Subscription cancelResolved(Subscription sub, String reason, boolean immediate) {
        if (sub.isTerminal()) {
            throw new co.subpilot.common.exception.InvalidStateTransitionException(
                    sub.getStatus().name(), SubscriptionStatus.cancelled.name());
        }

        if (immediate) {
            SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.cancelled);
            sub.setStatus(SubscriptionStatus.cancelled);
            sub.setCancelledAt(Instant.now());
            sub.setCancellationReason(reason);
        } else {
            sub.setCancelAtPeriodEnd(true);
            sub.setCancellationReason(reason);
        }

        Subscription saved = subscriptionRepository.save(sub);
        eventService.emit(sub.getMerchantId(), EventType.SUBSCRIPTION_CANCELLED, "subscription",
                sub.getId(), Map.of("reason", reason != null ? reason : "", "immediate", immediate));

        notificationService.sendSubscriptionCancelled(saved, reason);
        notificationService.sendSubscriptionCancelledMerchantAlert(saved, reason);

        log.info("Subscription cancelled: {} immediate={}", sub.getId(), immediate);
        return saved;
    }

    @Transactional
    public Subscription pause(String subscriptionId) {
        String merchantId = TenantContext.requireMerchantId();
        Subscription sub = requireSubscription(subscriptionId, merchantId);

        SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.paused);
        sub.setStatus(SubscriptionStatus.paused);
        sub.setPausedAt(Instant.now());
        Subscription saved = subscriptionRepository.save(sub);
        eventService.emit(merchantId, EventType.SUBSCRIPTION_PAUSED, "subscription", sub.getId(), Map.of());
        return saved;
    }

    @Transactional
    public Subscription resume(String subscriptionId) {
        String merchantId = TenantContext.requireMerchantId();
        Subscription sub = requireSubscription(subscriptionId, merchantId);

        SubscriptionStateMachine.assertCanTransition(sub.getStatus(), SubscriptionStatus.active);
        sub.setStatus(SubscriptionStatus.active);
        sub.setPausedAt(null);
        Subscription saved = subscriptionRepository.save(sub);
        eventService.emit(merchantId, EventType.SUBSCRIPTION_RESUMED, "subscription", sub.getId(), Map.of());
        return saved;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Page<Subscription> list(String status, String planId, Pageable pageable) {
        String merchantId = TenantContext.requireMerchantId();
        if (status != null) {
            SubscriptionStatus parsed = SubscriptionStatus.valueOf(status);
            return subscriptionRepository.findByMerchantIdAndStatus(merchantId, parsed, pageable);
        }
        if (planId != null) return subscriptionRepository.findByMerchantIdAndPlanId(merchantId, planId, pageable);
        return subscriptionRepository.findByMerchantId(merchantId, pageable);
    }

    public Subscription getById(String subscriptionId) {
        String merchantId = TenantContext.requireMerchantId();
        return requireSubscription(subscriptionId, merchantId);
    }

    // Portal — resolves by token, no merchant auth required.
    public Subscription getByToken(String token) {
        return subscriptionRepository.findBySubscriptionToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("subscription", token));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Subscription requireSubscription(String id, String merchantId) {
        return subscriptionRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("subscription", id));
    }
}