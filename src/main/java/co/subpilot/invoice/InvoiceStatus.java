package co.subpilot.invoice;

/**
 * Invoice status values. Plain String constants instead of an enum because
 * "void" is a Java reserved word and can't be an enum constant.
 * Matches PRD §6.5 and frontend BACKEND_HANDOFF.md exactly:
 *   draft (frontend) is treated as 'pending' here for PRD alignment.
 */
public final class InvoiceStatus {
    public static final String PENDING = "pending";
    public static final String PAID = "paid";
    public static final String FAILED = "failed";
    public static final String VOID = "void";
    public static final String REFUNDED = "refunded";

    private InvoiceStatus() {}
}