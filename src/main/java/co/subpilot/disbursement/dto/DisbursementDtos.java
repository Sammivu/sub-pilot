package co.subpilot.disbursement.dto;

public class DisbursementDtos {

    public record DisbursementResponse(
            String id,
            long amount,
            String currency,
            String status,
            int invoiceCount,
            String periodStart,
            String periodEnd,
            String nombaTransferReference,
            String failureReason,
            String createdAt,
            String resolvedAt
    ) {
        public static DisbursementResponse from(co.subpilot.disbursement.entity.Disbursement d) {
            return new DisbursementResponse(
                    d.getId(), d.getAmount(), d.getCurrency(), d.getStatus(), d.getInvoiceCount(),
                    d.getPeriodStart() != null ? d.getPeriodStart().toString() : null,
                    d.getPeriodEnd().toString(),
                    d.getNombaTransferReference(), d.getFailureReason(),
                    d.getCreatedAt() != null ? d.getCreatedAt().toString() : null,
                    d.getResolvedAt() != null ? d.getResolvedAt().toString() : null
            );
        }
    }
}