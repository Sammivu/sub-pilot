package co.subpilot.nomba;

/**
 * Prefixes used on the orderReference passed to Nomba's checkout order API.
 *
 * Nomba does NOT reliably echo back arbitrary caller-supplied metadata on
 * the inbound payment_success webhook (confirmed against real sandbox/
 * production payload examples in developer.nomba.com — orderMetaData is
 * not present in any documented webhook payload). The orderReference field
 * IS guaranteed to round-trip verbatim ("The orderReference in the response
 * is the same value you passed in the request"), so that's the only
 * reliable channel for routing information back out of a webhook.
 *
 * Every orderReference SubPilot generates therefore follows
 * "{purposePrefix}{subscriptionId}", and WebhookController parses the
 * prefix back out instead of trying to read a metadata field that Nomba
 * doesn't actually send back.
 */
public final class CheckoutPurpose {
    public static final String NEW_SUBSCRIPTION_PREFIX = "checkout_";
    public static final String CARD_UPDATE_PREFIX = "card_update_";
    public static final String TRANSFER_PREFIX = "transfer_";

    private CheckoutPurpose() {}

    /** Extracts the subscriptionId from an orderReference built with one of the prefixes above, or null if it matches neither. */
    public static String extractSubscriptionId(String orderReference) {
        if (orderReference == null) return null;
        if (orderReference.startsWith(NEW_SUBSCRIPTION_PREFIX)) {
            return orderReference.substring(NEW_SUBSCRIPTION_PREFIX.length());
        }
        if (orderReference.startsWith(CARD_UPDATE_PREFIX)) {
            return orderReference.substring(CARD_UPDATE_PREFIX.length());
        }
        if (orderReference.startsWith(TRANSFER_PREFIX))         return orderReference.substring(TRANSFER_PREFIX.length());

        return null;
    }

    public static boolean isTransfer(String ref) {
        return ref != null && ref.startsWith(TRANSFER_PREFIX);
    }

    public static boolean isCardUpdate(String orderReference) {
        return orderReference != null && orderReference.startsWith(CARD_UPDATE_PREFIX);
    }

    public static boolean isNewSubscription(String orderReference) {
        return orderReference != null && orderReference.startsWith(NEW_SUBSCRIPTION_PREFIX);
    }
}