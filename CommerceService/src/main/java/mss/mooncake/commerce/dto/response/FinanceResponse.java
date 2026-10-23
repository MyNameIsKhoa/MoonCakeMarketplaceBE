package mss.mooncake.commerce.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FinanceResponse(Context context, List<Payment> payments, List<Case> cancellations,
        List<Case> refunds, List<Case> disputes, List<Settlement> settlements,
        Eligibility eligibility, List<Audit> history) {
    public record Context(long sellerOrderId, long totalAmount, Long depositAmount,
            LocalDate deliveryDate, boolean accepted, long paidAmount, Instant remainingDueAt) { }
    public record Payment(long paymentId, long sellerOrderId, String paymentType, long amount,
            String status, String holdStatus, Instant dueAt, Instant expiresAt,
            String checkoutUrl, String qrCode) { }
    public record Case(long id, String status, String reason, Long amount, String evidenceUrl,
            String resolution) { }
    public record Settlement(long settlementId, long grossAmount, String feeBasis,
            String roundingRule, long feeAmount, long supplierPayoutAmount, String status) { }
    public record Eligibility(boolean eligible, List<String> blockingReasons) { }
    public record Audit(long auditId, String eventType, String actor, String detail, Instant createdAt) { }
}
