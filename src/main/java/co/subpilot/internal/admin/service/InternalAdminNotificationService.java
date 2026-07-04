package co.subpilot.internal.admin.service;

import co.subpilot.internal.admin.repository.InternalAdminRepository;
import co.subpilot.notification.service.EmailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT built on top of NotificationService/EmailDispatcher —
 * those are subscription-centric (every send requires a merchantId +
 * subscriptionId and logs to NotificationLog against that pair), which
 * doesn't fit "tell every internal admin something needs their attention."
 * This is the direct answer to "how will admins know" — they may not sit
 * on the dashboard, so GET /v1/internal/dashboard/summary alone isn't
 * enough; this pushes an email the moment something enters a pending
 * queue, without waiting for anyone to check.
 *
 * No delivery log table for these in V1 (unlike NotificationLog) — kept
 * deliberately lightweight; add one if silent admin-email failures ever
 * become a real support problem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalAdminNotificationService {

    private final InternalAdminRepository internalAdminRepository;
    private final EmailSender emailSender;

    @Async("asyncExecutor")
    public void notifyAllAdmins(String subject, String htmlBody, String tag) {
        internalAdminRepository.findAll().forEach(admin -> {
            EmailSender.EmailSendResult result = emailSender.send(
                    admin.getEmail(), admin.getDisplayName(), subject, htmlBody, tag);
            if (!result.success()) {
                log.warn("Failed to notify admin={} of '{}': {}", admin.getEmail(), tag, result.errorMessage());
            }
        });
    }

    public void notifyNewMerchantSignup(String merchantId, String businessName, String email) {
        notifyAllAdmins(
                "New merchant signed up: " + businessName,
                "<p>A new merchant has signed up.</p>" +
                        "<p><b>" + escape(businessName) + "</b> (" + escape(email) + ")<br/>" +
                        "Merchant ID: " + escape(merchantId) + "</p>" +
                        "<p>They're already active — this is FYI, not an approval request. " +
                        "View them in the internal admin dashboard.</p>",
                "merchant_signed_up"
        );
    }

    public void notifyMerchantUnderReview(String merchantId, String businessName, String reason) {
        notifyAllAdmins(
                "Merchant flagged for review: " + businessName,
                "<p><b>" + escape(businessName) + "</b> (" + escape(merchantId) + ") was moved to under_review.</p>" +
                        "<p>Reason: " + escape(reason) + "</p>",
                "merchant_under_review"
        );
    }

    public void notifyRefundPendingApproval(String refundId, String merchantId, long amount) {
        notifyAllAdmins(
                "Refund awaiting approval",
                "<p>A refund of <b>" + amount + "</b> (minor units) for merchant " + escape(merchantId) +
                        " is awaiting approval.</p><p>Refund ID: " + escape(refundId) + "</p>",
                "refund_pending_approval"
        );
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("<", "&lt;").replace(">", "&gt;");
    }
}