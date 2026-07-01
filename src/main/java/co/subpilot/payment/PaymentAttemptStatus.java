package co.subpilot.payment;

public final class PaymentAttemptStatus {
    public static final String PENDING = "pending";
    public static final String PROCESSING = "processing";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    private PaymentAttemptStatus() {}
}