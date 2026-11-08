package mss.mooncake.commerce.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.dto.internal.FinanceEvent;
import mss.mooncake.commerce.dto.response.FinanceResponse;

/** Persistence contract for the finance aggregate. */
public interface FinanceRepository {
    record Context(long id, long customerId, long supplierId, long total, Long deposit,
            LocalDate deliveryDate, boolean accepted, long sourceVersion, long fulfillmentVersion,
            boolean delivered, boolean received) { }
    record Payment(long id, long orderId, String type, long amount, String status,
            String hold, Long orderCode, String linkId, String key, Instant dueAt,
            Instant expiresAt, String checkoutUrl, String qrCode) {
        public FinanceResponse.Payment response() {
            return new FinanceResponse.Payment(id, orderId, type, amount, status, hold, dueAt,
                    expiresAt, checkoutUrl, qrCode);
        }
    }
    record CustomerPayment(long id, long orderId, long orderCode, String key, long amount,
            String status, String linkId, String checkoutUrl, String qrCode, Instant expiresAt) { }
    record CustomerPaymentItem(long paymentId, long sellerOrderId, long amount, String sellerOrderNo) { }
    record StockLine(long variantId, int quantity) { }
    /** A supplier's seller order with its settlement snapshot (null fields when none was created yet). */
    record SupplierPayout(long sellerOrderId, String sellerOrderNo, long orderId, String orderNo, String storeName,
            String status, Instant placedAt, Long settlementId, String settlementStatus, Long feeAmount,
            Long payoutAmount, Long refundedAmount, String payoutReference, Instant settledAt) { }

    static Timestamp utc(Instant value) {
        return value == null ? null : Timestamp.valueOf(java.time.LocalDateTime.ofInstant(value, java.time.ZoneOffset.UTC));
    }

    int update(String sql, Object... args);
    long count(String sql, Object... args);
    long insert(String sql, Object... args);
    Context context(long orderId);
    Context lock(long orderId);
    boolean hasContext(long orderId);
    boolean matchesPersistedOrder(FinanceOrderContext context);
    Payment payment(long id);
    Payment byOrderCode(long code);
    List<Payment> payments(long orderId);
    long paid(long orderId);
    long nextOrderCode();
    String customerOrderNumber(long orderId);
    String sellerOrderNumber(long sellerOrderId);
    boolean active(long orderId);
    long parentOrderId(long sellerOrderId);
    List<String> sellerStatuses(long orderId);
    List<Long> pendingCancellationOrderIds();
    List<Long> overdueRfqOrderIds(Instant placedBefore);
    List<Long> overdueRetailOrderIds(Instant placedBefore);
    /** Retail checkouts with confirmed stock still to return: fully cancelled, or with a cancelled seller order. */
    List<Long> unrestoredCancelledRetailOrderIds();
    /** Cancelled seller orders of the checkout whose own lines were not returned to Catalog yet. */
    List<Long> unrestoredCancelledSellerOrderIds(long orderId);
    /** Units per Catalog variant ordered in one seller order (what its cancellation returns to stock). */
    List<StockLine> sellerOrderStockLines(long sellerOrderId);
    /** DELIVERED seller orders the buyer never accepted, last delivered at or before the cutoff, never disputed since. */
    List<Long> autoAcceptableSellerOrderIds(Instant deliveredBefore);
    void lockCustomerOrder(long orderId);
    long customerOrderOwner(long orderId);
    long customerOrderTotal(long orderId);
    String stockReservationKey(long orderId);
    boolean retailOrder(long orderId);
    List<Long> sellerOrderIds(long orderId);
    CustomerPayment customerPayment(long id);
    List<CustomerPayment> customerPayments(long orderId);
    List<CustomerPayment> customerPaymentByCode(long code);
    List<CustomerPaymentItem> customerPaymentItems(long id);
    boolean activeCustomerPayment(long sellerOrderId);
    List<FinanceResponse.Case> cancellations(long orderId);
    List<FinanceResponse.Case> refunds(long orderId);
    List<FinanceResponse.Case> disputes(long orderId);
    java.time.Instant deliveredAt(long orderId);
    java.time.Instant expectedDeliveryAt(long orderId);
    /** When the latest successful collection was recorded, or null if nothing was collected. */
    java.time.Instant lastPaidAt(long orderId);
    long refunded(long orderId);
    long refundedForDispute(long disputeId);
    List<Long> pendingDisputeOrderIds(int offset, int limit);
    List<FinanceResponse.Settlement> settlements(long orderId);
    /** Every seller order of the supplier that has a finance context, newest first. */
    List<SupplierPayout> supplierPayouts(long supplierId);
    List<FinanceResponse.Audit> history(long orderId);
    void event(long orderId, String type, long resourceId, String actor, String detail);
    void ledger(long orderId, String key, String type, long amount);
    List<FinanceEvent> pendingEvents();
}
