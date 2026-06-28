package co.subpilot.portal.controller;

import co.subpilot.common.exception.ResourceNotFoundException;
import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.invoice.repository.InvoiceRepository;
import co.subpilot.nomba.NombaPaymentGateway;
import co.subpilot.plan.PlanStatus;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import co.subpilot.portal.dto.PortalDtos;
import co.subpilot.proration.dto.ProrationDtos;
import co.subpilot.proration.service.ProrationService;
import co.subpilot.subscription.entity.Subscription;
import co.subpilot.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The customer self-service portal (PRD §6.7).
 *
 * Accessed at /v1/portal/{subscriptionToken} — no account, no JWT, no API
 * key. The opaque subscription_token (a non-guessable UUID generated when
 * the subscription was created — see Subscription.ensureToken) IS the
 * authentication. Anyone holding the token — which only ever reaches the
 * subscriber via their confirmation/billing emails — can view and manage
 * that one subscription. This mirrors how Stripe's customer portal session
 * links work.
 *
 * Every endpoint here resolves the Subscription by token FIRST, then uses
 * the resolved subscription's own merchantId for all subsequent scoping —
 * TenantContext is never populated on these routes (see SecurityConfig:
 * /v1/portal/** is permitAll), so no method here may rely on it.
 *
 * Deliberately exposes only what a subscriber should see — see PortalDtos
 * for the slim, hand-picked projections used instead of raw entities.
 */
@RestController
@RequestMapping("/v1/portal/{subscriptionToken}")
@RequiredArgsConstructor
public class PortalController {

    private final SubscriptionService subscriptionService;
    private final ProrationService prorationService;
    private final PlanRepository planRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final NombaPaymentGateway nomba;

    @Value("${subpilot.frontend.base-url}")
    private String frontendBaseUrl;

    // ── View subscription + next billing date ─────────────────────────────────

    @GetMapping
    public ResponseEntity<PortalDtos.PortalSubscriptionView> getSubscription(@PathVariable String token) {
        Subscription sub = subscriptionService.getByToken(token);
        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("plan", sub.getPlanId()));
        Customer customer = customerRepository.findByIdAndMerchantId(sub.getCustomerId(), sub.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("customer", sub.getCustomerId()));

        return ResponseEntity.ok(PortalDtos.PortalSubscriptionView.from(
                sub, plan, customer.getCardLast4(), customer.getCardBrand()));
    }

    // ── Invoice history (PRD §6.7: "view invoice history") ─────────────────────

    @GetMapping("/invoices")
    public ResponseEntity<List<PortalDtos.PortalInvoiceView>> getInvoices(@PathVariable String token) {
        Subscription sub = subscriptionService.getByToken(token);
        List<Invoice> invoices = invoiceRepository.findBySubscriptionIdOrderByCreatedAtDesc(sub.getId());
        return ResponseEntity.ok(invoices.stream().map(PortalDtos.PortalInvoiceView::from).toList());
    }

    // ── Cancel (PRD §6.7: "cancel without contacting the merchant") ────────────

    @PostMapping("/cancel")
    public ResponseEntity<PortalDtos.PortalSubscriptionView> cancel(
            @PathVariable String token,
            @Valid @RequestBody(required = false) PortalDtos.PortalCancelRequest req) {
        Subscription sub = subscriptionService.getByToken(token);
        String reason = req != null ? req.reason() : null;

        Subscription cancelled = subscriptionService.cancelViaPortal(sub, reason);
        Plan plan = planRepository.findById(cancelled.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("plan", cancelled.getPlanId()));
        Customer customer = customerRepository.findByIdAndMerchantId(cancelled.getCustomerId(), cancelled.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("customer", cancelled.getCustomerId()));

        return ResponseEntity.ok(PortalDtos.PortalSubscriptionView.from(
                cancelled, plan, customer.getCardLast4(), customer.getCardBrand()));
    }

    // ── Update payment method (PRD §6.7) ────────────────────────────────────────
    // Re-tokenises the card via a fresh Nomba Checkout session. The actual
    // card swap happens when that checkout completes and calls back through
    // SubscriptionService.activateAfterCheckout — same confirmation path as
    // initial signup, just re-used here for a card update rather than a new
    // subscription.

    @PostMapping("/update-card")
    public ResponseEntity<PortalDtos.PortalUpdateCardResponse> updateCard(@PathVariable String token) {
        Subscription sub = subscriptionService.getByToken(token);
        Plan plan = planRepository.findById(sub.getPlanId())
                .orElseThrow(() -> new ResourceNotFoundException("plan", sub.getPlanId()));
        Customer customer = customerRepository.findByIdAndMerchantId(sub.getCustomerId(), sub.getMerchantId())
                .orElseThrow(() -> new ResourceNotFoundException("customer", sub.getCustomerId()));

        // A nominal re-auth amount isn't charged to the customer here — this
        // checkout's sole purpose is capturing a fresh card token. Nomba's
        // checkout flow requires an amount field regardless, so we pass the
        // plan's normal price; the actual settlement still only happens on
        // the next real billing-engine renewal, not from this checkout.
        String callbackUrl = frontendBaseUrl + "/portal/" + token + "/card-updated";

        NombaPaymentGateway.CheckoutResponse checkout = nomba.initiateCheckout(
                new NombaPaymentGateway.CheckoutRequest(
                        "card_update_" + sub.getId(),
                        plan.getAmount(),
                        plan.getCurrency(),
                        customer.getEmail(),
                        customer.getFullName(),
                        customer.getPhone(),
                        callbackUrl,
                        "{\"subscriptionId\":\"" + sub.getId() + "\",\"purpose\":\"card_update\"}"
                )
        );

        return ResponseEntity.ok(new PortalDtos.PortalUpdateCardResponse(checkout.checkoutUrl(), checkout.reference()));
    }

    // ── Upgrade / downgrade plan (PRD §6.7: "upgrade/downgrade plan") ───────────

    @GetMapping("/available-plans")
    public ResponseEntity<List<PortalDtos.PortalAvailablePlan>> getAvailablePlans(@PathVariable String token) {
        Subscription sub = subscriptionService.getByToken(token);

        List<Plan> plans = planRepository
                .findByMerchantIdAndStatus(sub.getMerchantId(), PlanStatus.published,
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent();

        return ResponseEntity.ok(plans.stream()
                .filter(p -> !p.getId().equals(sub.getPlanId())) // exclude the plan they're already on
                .map(p -> new PortalDtos.PortalAvailablePlan(
                        p.getId(), p.getName(), p.getAmount(), p.getCurrency(), p.getBillingInterval().name()))
                .toList());
    }

    @PostMapping("/change-plan")
    public ResponseEntity<ProrationDtos.ChangePlanResponse> changePlan(
            @PathVariable String token,
            @Valid @RequestBody PortalDtos.PortalChangePlanRequest req) {
        Subscription sub = subscriptionService.getByToken(token);
        return ResponseEntity.ok(prorationService.changePlanViaPortal(sub, req.newPlanId()));
    }
}