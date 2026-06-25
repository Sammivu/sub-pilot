package co.subpilot.common.tenant;

/**
 * Thread-local holder for the current authenticated merchant ID.
 *
 * Set once per request by MerchantContextFilter.
 * All service/repository calls read from here — no merchant_id ever comes from
 * the request body, only from the authenticated principal.
 *
 * With Java 21 virtual threads, InheritableThreadLocal is safer than ThreadLocal
 * for async contexts.
 */
public final class TenantContext {

    private static final InheritableThreadLocal<String> MERCHANT_ID =
            new InheritableThreadLocal<>();

    private TenantContext() {}

    public static void setMerchantId(String merchantId) {
        MERCHANT_ID.set(merchantId);
    }

    public static String getMerchantId() {
        return MERCHANT_ID.get();
    }

    /**
     * Returns the merchant ID or throws — use this in any service that
     * absolutely requires a tenant context (i.e., all protected endpoints).
     */
    public static String requireMerchantId() {
        String id = MERCHANT_ID.get();
        if (id == null) {
            throw new IllegalStateException(
                "No merchant context set. This method must only be called from authenticated requests."
            );
        }
        return id;
    }

    public static void clear() {
        MERCHANT_ID.remove();
    }
}
