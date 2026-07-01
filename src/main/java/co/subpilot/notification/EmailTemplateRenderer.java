package co.subpilot.notification;

import co.subpilot.notification.enums.EmailTemplate;

import java.util.Map;

/**
 * Renders the HTML body for each EmailTemplate.
 *
 * Deliberately dependency-free (no Thymeleaf/Freemarker) — just a shared
 * inline-styled HTML shell with {{placeholder}} substitution. This keeps
 * the notification module self-contained and easy to extend; swap in a
 * real templating engine later if the design needs grow beyond this.
 */
public final class EmailTemplateRenderer {

    private EmailTemplateRenderer() {}

    public static String render(EmailTemplate template, Map<String, String> vars) {
        String title;
        String bodyHtml;

        switch (template) {
            case SUBSCRIPTION_ACTIVATED -> {
                title = "You're subscribed!";
                bodyHtml = """
                    <p>Hi {{customerName}},</p>
                    <p>Your subscription to <strong>{{planName}}</strong> with {{merchantName}} is now active.</p>
                    <p>You'll be billed <strong>{{amount}}</strong> every {{billingInterval}}, starting today.</p>
                    <p><a href="{{portalUrl}}" style="color:#4f46e5;">Manage your subscription</a></p>
                    """;
            }
            case PAYMENT_SUCCEEDED -> {
                title = "Payment received";
                bodyHtml = """
                    <p>Hi {{customerName}},</p>
                    <p>We've successfully charged <strong>{{amount}}</strong> for your {{planName}} subscription with {{merchantName}}.</p>
                    <p><a href="{{invoiceUrl}}" style="color:#4f46e5;">View your invoice</a></p>
                    """;
            }
            case PAYMENT_FAILED -> {
                title = "Payment failed";
                bodyHtml = """
                    <p>Hi {{customerName}},</p>
                    <p>We couldn't process your payment of <strong>{{amount}}</strong> for your {{planName}} subscription with {{merchantName}}.</p>
                    <p>Reason: {{failureReason}}</p>
                    <p>Please update your payment method to keep your subscription active:</p>
                    <p><a href="{{selfCureUrl}}" style="color:#4f46e5;">Update payment method</a></p>
                    """;
            }
            case DUNNING_WARNING -> {
                title = "Action needed: your subscription will be suspended soon";
                bodyHtml = """
                    <p>Hi {{customerName}},</p>
                    <p>We still haven't been able to process payment for your {{planName}} subscription with {{merchantName}}.</p>
                    <p><strong>Your subscription will be cancelled in {{daysRemaining}} day(s)</strong> unless payment succeeds.</p>
                    <p><a href="{{selfCureUrl}}" style="color:#4f46e5;">Update payment method now</a></p>
                    """;
            }
            case SUBSCRIPTION_SUSPENDED -> {
                title = "Your subscription has been suspended";
                bodyHtml = """
                    <p>Hi {{customerName}},</p>
                    <p>We were unable to recover payment for your {{planName}} subscription with {{merchantName}}
                    within the grace period, so your subscription has now been suspended.</p>
                    <p>You can still restore it at any time by updating your payment method — your account and
                    data are kept safe in the meantime.</p>
                    <p><a href="{{selfCureUrl}}" style="color:#4f46e5;">Restore my subscription</a></p>
                    """;
            }
            case SUBSCRIPTION_CANCELLED -> {
                title = "Your subscription has been cancelled";
                bodyHtml = """
                    <p>Hi {{customerName}},</p>
                    <p>Your subscription to <strong>{{planName}}</strong> with {{merchantName}} has been cancelled.</p>
                    <p>{{cancellationNote}}</p>
                    """;
            }
            case NEW_SUBSCRIBER -> {
                title = "New subscriber!";
                bodyHtml = """
                    <p>Hi {{merchantName}},</p>
                    <p><strong>{{customerName}}</strong> ({{customerEmail}}) just subscribed to <strong>{{planName}}</strong>.</p>
                    <p><a href="{{dashboardUrl}}" style="color:#4f46e5;">View in dashboard</a></p>
                    """;
            }
            case PAYMENT_FAILED_MERCHANT -> {
                title = "A subscriber's payment failed";
                bodyHtml = """
                    <p>Hi {{merchantName}},</p>
                    <p>A payment of <strong>{{amount}}</strong> from <strong>{{customerName}}</strong> ({{customerEmail}}) for {{planName}} has failed.</p>
                    <p>Reason: {{failureReason}}</p>
                    <p>Dunning has started automatically — we'll keep retrying and notify you of the outcome.</p>
                    """;
            }
            case DUNNING_EXHAUSTED_MERCHANT -> {
                title = "Subscriber payment recovery failed";
                bodyHtml = """
                    <p>Hi {{merchantName}},</p>
                    <p>We were unable to recover payment from <strong>{{customerName}}</strong> ({{customerEmail}}) for {{planName}} after multiple attempts.</p>
                    <p>This subscription has now been cancelled.</p>
                    """;
            }
            case SUBSCRIPTION_CANCELLED_MERCHANT -> {
                title = "A subscription was cancelled";
                bodyHtml = """
                    <p>Hi {{merchantName}},</p>
                    <p><strong>{{customerName}}</strong> ({{customerEmail}})'s subscription to {{planName}} has been cancelled.</p>
                    <p>Reason: {{cancellationNote}}</p>
                    """;
            }
            default -> {
                title = "Notification";
                bodyHtml = "<p>{{message}}</p>";
            }
        }

        String filledBody = interpolate(bodyHtml, vars);
        return wrapInLayout(title, filledBody);
    }

    private static String interpolate(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        // Strip any unresolved placeholders rather than leaking {{varName}} into the sent email
        result = result.replaceAll("\\{\\{[a-zA-Z0-9_]+\\}\\}", "");
        return result;
    }

    private static String wrapInLayout(String title, String bodyHtml) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body style="margin:0;padding:0;background:#f4f4f7;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
              <div style="max-width:560px;margin:32px auto;background:#ffffff;border-radius:8px;padding:32px;color:#1f2937;">
                <h2 style="margin:0 0 16px 0;color:#111827;">%s</h2>
                <div style="font-size:15px;line-height:1.6;">
                  %s
                </div>
                <hr style="margin-top:32px;border:none;border-top:1px solid #e5e7eb;">
                <p style="font-size:12px;color:#9ca3af;margin-top:16px;">Sent by SubPilot on behalf of your subscription provider.</p>
              </div>
            </body>
            </html>
            """.formatted(title, bodyHtml);
    }
}