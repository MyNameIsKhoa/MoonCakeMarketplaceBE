package mss.mooncake.commerce.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record FinanceResponse(Context context, List<Payment> payments, List<Case> cancellations,
        List<Case> refunds, List<Case> disputes, List<Settlement> settlements,
        Eligibility eligibility, List<Audit> history) {
    public record Context(long sellerOrderId, long totalAmount, Long depositAmount,
            LocalDate deliveryDate, boolean accepted, long paidAmount, Instant remainingDueAt,
            Instant disputeDeadlineAt) { }
    public record Payment(long paymentId, long sellerOrderId, String paymentType, long amount,
            String status, String holdStatus, Instant dueAt, Instant expiresAt,
            String checkoutUrl, String qrCode, Long refundableAmount) {
        public Payment(long paymentId, long sellerOrderId, String paymentType, long amount,
                       String status, String holdStatus, Instant dueAt, Instant expiresAt,
                       String checkoutUrl, String qrCode) {
            this(paymentId, sellerOrderId, paymentType, amount, status, holdStatus, dueAt, expiresAt,
                    checkoutUrl, qrCode, null);
        }
    }
    public record Case(long id, String status, String reason, Long amount, String evidenceUrl,
            String resolution, String sellerResponse, Instant openedAt, Instant respondedAt,
            Instant escalatedAt, String decisionType, Long disputeId) {
        public Case(long id, String status, String reason, Long amount, String evidenceUrl, String resolution) {
            this(id, status, reason, amount, evidenceUrl, resolution, null, null, null, null, null, null);
        }
    }
    public record Settlement(long settlementId, long grossAmount, String feeBasis,
            String roundingRule, long feeAmount, long supplierPayoutAmount, String status,
            long refundedAmount) {
        public Settlement(long settlementId, long grossAmount, String feeBasis, String roundingRule,
                long feeAmount, long supplierPayoutAmount, String status) {
            this(settlementId, grossAmount, feeBasis, roundingRule, feeAmount, supplierPayoutAmount, status, 0);
        }
    }
    public record Eligibility(boolean eligible, List<String> blockingReasons) { }

    /**
     * A supplier's money per seller order (newest first) and totals over all of its seller orders. Amounts are exact
     * VND. Until a settlement snapshot is READY/PROCESSING/SUCCESS, fee and payout are estimated
     * ({@code feeEstimated}) with the configured settlement rule on money collected minus confirmed refunds.
     * payoutStatus: PAID, PROCESSING (manual transfer reserved), READY (eligible or settlement READY), ON_HOLD,
     * BLOCKED (not eligible yet), NO_PAYOUT (cancelled or fully refunded). Totals: pending = BLOCKED + ON_HOLD,
     * ready = READY + PROCESSING, paid = PAID.
     */
    public record SupplierPayouts(List<Row> content, int page, int size, long totalElements, int totalPages,
            Totals totals) {
        public record Row(long sellerOrderId, String sellerOrderNo, long orderId, String orderNo, String storeName,
                String orderStatus, Instant placedAt, long totalAmount, long grossCollected, long refundedAmount,
                Long platformFee, Long netPayout, boolean feeEstimated, boolean eligible, List<String> blockingReasons,
                Long settlementId, String settlementStatus, String payoutStatus, String payoutReference,
                Instant paidAt) { }
        public record Totals(long pendingAmount, long readyAmount, long processingAmount, long paidAmount) { }
    }
    /** Admin finance list: one page of every seller order (newest first), rows as in {@link SupplierPayouts}. */
    public record AdminPayouts(List<SupplierPayouts.Row> content, int page, int size, long totalElements,
            int totalPages) { }
    public record Audit(long auditId, String eventType, long resourceId, String actor, String detail, Instant createdAt) { }
}
