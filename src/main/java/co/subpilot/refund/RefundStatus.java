package co.subpilot.refund;

public final class RefundStatus {
    public static final String PENDING_APPROVAL = "pending_approval";
    public static final String PENDING = "pending";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String REJECTED = "rejected";

    private RefundStatus() {}
}