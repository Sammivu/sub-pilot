package co.subpilot.notification.service;

import co.subpilot.customer.entity.Customer;
import co.subpilot.customer.repository.CustomerRepository;
import co.subpilot.invoice.entity.Invoice;
import co.subpilot.merchant.entity.Merchant;
import co.subpilot.merchant.repository.MerchantRepository;
import co.subpilot.notification.entity.NotificationLog;
import co.subpilot.notification.enums.EmailTemplate;
import co.subpilot.notification.repository.NotificationLogRepository;
import co.subpilot.plan.entity.Plan;
import co.subpilot.plan.repository.PlanRepository;
import co.subpilot.subscription.entity.Subscription;
import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Single entry point for every transactional email SubPilot sends.
 *
 * Implements PRD §6.9 in full:
 *   Merchant notifications: new subscriber, payment failed, dunning
 *   exhausted, subscription cancelled.
 *   Subscriber notifications: subscription activated, payment
 *   succeeded/failed, dunning warning, subscription cancelled.
 *
 * Every send is logged to NotificationLog regardless of outcome — this is
 * the audit trail support can use to answer "did my customer get that
 * email" without digging through application logs.
 *
 * Sending happens off the calling thread via EmailDispatcher (a separate
 * @Async-annotated bean) so a slow or failing email provider never adds
 * latency to — or rolls back — the business transaction that triggered the
 * notification (e.g. a billing engine run or a dunning step). The
 * NotificationLog row is still written synchronously here first, in
 * "pending" state, so there's a durable record even if the app crashes
 * mid-send.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final EmailDispatcher emailDispatcher;
    private final NotificationLogRepository notificationLogRepository;
    private final MerchantRepository merchantRepository;
    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;

    @Value("${subpilot.frontend.base-url}")
    private String frontendBaseUrl;

    // ── Subscriber-facing notifications ──────────────────────────────────────

    public void sendSubscriptionActivated(Subscription sub) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("portalUrl", portalUrl(sub.getSubscriptionToken()));
            vars.put("billingInterval", plan.getBillingInterval().name().toLowerCase());
            send(merchant.getId(), sub.getId(), customer.getEmail(), customer.getFullName(),
                    EmailTemplate.SUBSCRIPTION_ACTIVATED, vars);
        });
    }

    public void sendPaymentSucceeded(Subscription sub, Invoice invoice) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("amount", formatAmount(invoice.getAmount(), invoice.getCurrency()));
            vars.put("invoiceUrl", portalUrl(sub.getSubscriptionToken()) + "/invoices/" + invoice.getId());
            send(merchant.getId(), sub.getId(), customer.getEmail(), customer.getFullName(),
                    EmailTemplate.PAYMENT_SUCCEEDED, vars);
        });
    }

    public void sendPaymentFailed(Subscription sub, Invoice invoice, String failureReason) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("amount", formatAmount(invoice.getAmount(), invoice.getCurrency()));
            vars.put("failureReason", failureReason != null ? failureReason : "Payment could not be processed.");
            vars.put("selfCureUrl", portalUrl(sub.getSubscriptionToken()));
            send(merchant.getId(), sub.getId(), customer.getEmail(), customer.getFullName(),
                    EmailTemplate.PAYMENT_FAILED, vars);
        });
    }

    public void sendDunningWarning(Subscription sub, int daysRemaining) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("daysRemaining", String.valueOf(daysRemaining));
            vars.put("selfCureUrl", portalUrl(sub.getSubscriptionToken()));
            send(merchant.getId(), sub.getId(), customer.getEmail(), customer.getFullName(),
                    EmailTemplate.DUNNING_WARNING, vars);
        });
    }

    /**
     * Fires once at the moment grace period elapses and the subscription
     * transitions to 'suspended' — distinct from sendDunningWarning, which
     * is the earlier "you have N days left" countdown. See
     * DunningTriggerService.suspendIfNotAlready for the trigger point.
     */
    public void sendSubscriptionSuspended(Subscription sub) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("selfCureUrl", portalUrl(sub.getSubscriptionToken()));
            send(merchant.getId(), sub.getId(), customer.getEmail(), customer.getFullName(),
                    EmailTemplate.SUBSCRIPTION_SUSPENDED, vars);
        });
    }

    public void sendSubscriptionCancelled(Subscription sub, String reason) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("cancellationNote", reason != null ? reason : "This subscription is no longer active.");
            send(merchant.getId(), sub.getId(), customer.getEmail(), customer.getFullName(),
                    EmailTemplate.SUBSCRIPTION_CANCELLED, vars);
        });
    }

    // ── Merchant-facing notifications ────────────────────────────────────────

    public void sendNewSubscriberAlert(Subscription sub) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("dashboardUrl", frontendBaseUrl + "/app/subscriptions/" + sub.getId());
            send(merchant.getId(), sub.getId(), merchant.getEmail(), merchant.getBusinessName(),
                    EmailTemplate.NEW_SUBSCRIBER, vars);
        });
    }

    public void sendPaymentFailedMerchantAlert(Subscription sub, Invoice invoice, String failureReason) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("amount", formatAmount(invoice.getAmount(), invoice.getCurrency()));
            vars.put("failureReason", failureReason != null ? failureReason : "Payment could not be processed.");
            send(merchant.getId(), sub.getId(), merchant.getEmail(), merchant.getBusinessName(),
                    EmailTemplate.PAYMENT_FAILED_MERCHANT, vars);
        });
    }

    public void sendDunningExhaustedMerchantAlert(Subscription sub) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            send(merchant.getId(), sub.getId(), merchant.getEmail(), merchant.getBusinessName(),
                    EmailTemplate.DUNNING_EXHAUSTED_MERCHANT, vars);
        });
    }

    public void sendSubscriptionCancelledMerchantAlert(Subscription sub, String reason) {
        withContext(sub, (customer, plan, merchant) -> {
            Map<String, String> vars = baseVars(customer, plan, merchant);
            vars.put("cancellationNote", reason != null ? reason : "Cancelled.");
            send(merchant.getId(), sub.getId(), merchant.getEmail(), merchant.getBusinessName(),
                    EmailTemplate.SUBSCRIPTION_CANCELLED_MERCHANT, vars);
        });
    }

    // ── Core send + log pipeline ──────────────────────────────────────────────

    /**
     * Writes a "pending" NotificationLog row synchronously, then hands off
     * to EmailDispatcher (a separate bean) for the actual async send. The
     * hand-off must cross a real Spring proxy boundary — calling an @Async
     * method on `this` from within the same class is a no-op in Spring AOP,
     * since self-invocation bypasses the proxy entirely.
     */
    @Transactional
    public void send(String merchantId, String subscriptionId, String toEmail, String toName,
                     EmailTemplate template, Map<String, String> vars) {
        NotificationLog logRow = NotificationLog.builder()
                .id(UlidCreator.getMonotonicUlid().toString())
                .merchantId(merchantId)
                .subscriptionId(subscriptionId)
                .recipientEmail(toEmail)
                .template(template.name())
                .status("pending")
                .provider("brevo")
                .createdAt(Instant.now())
                .build();
        logRow = notificationLogRepository.save(logRow);

        emailDispatcher.dispatchAsync(logRow.getId(), toEmail, toName, template, vars);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ContextualSend {
        void apply(Customer customer, Plan plan, Merchant merchant);
    }

    /**
     * Resolves Customer/Plan/Merchant for a subscription once, then invokes
     * the given action. Logs and swallows lookup failures rather than
     * throwing — a missing notification is never worth failing the calling
     * business transaction (billing, dunning, cancellation) over.
     */
    private void withContext(Subscription sub, ContextualSend action) {
        try {
            Customer customer = customerRepository.findById(sub.getCustomerId()).orElse(null);
            Plan plan = planRepository.findById(sub.getPlanId()).orElse(null);
            Merchant merchant = merchantRepository.findById(sub.getMerchantId()).orElse(null);

            if (customer == null || plan == null || merchant == null) {
                log.warn("Skipping notification for subscription={} — missing context (customer={}, plan={}, merchant={})",
                        sub.getId(), customer != null, plan != null, merchant != null);
                return;
            }

            action.apply(customer, plan, merchant);
        } catch (Exception e) {
            log.error("Failed to prepare notification for subscription={}: {}", sub.getId(), e.getMessage(), e);
        }
    }

    private Map<String, String> baseVars(Customer customer, Plan plan, Merchant merchant) {
        Map<String, String> vars = new HashMap<>();
        vars.put("customerName", customer.getFullName());
        vars.put("customerEmail", customer.getEmail());
        vars.put("planName", plan.getName());
        vars.put("merchantName", merchant.getBusinessName());
        return vars;
    }

    private String portalUrl(String subscriptionToken) {
        return frontendBaseUrl + "/portal/" + subscriptionToken;
    }

    private String formatAmount(long minorUnits, String currency) {
        BigDecimal major = BigDecimal.valueOf(minorUnits).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        String symbol = "NGN".equalsIgnoreCase(currency) ? "₦" : currency + " ";
        return symbol + major.toPlainString();
    }
}