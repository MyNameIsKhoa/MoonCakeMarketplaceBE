package mss.mooncake.commerce.repository.impl;

import java.sql.Timestamp;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.Context;
import mss.mooncake.commerce.repository.FinanceRepository.Payment;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPayment;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPaymentItem;
import mss.mooncake.commerce.repository.FinanceRepository.StockLine;
import mss.mooncake.commerce.repository.FinanceRepository.SupplierPayout;
import mss.mooncake.commerce.repository.FinanceRepository.TransferDestination;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.dto.internal.FinanceEvent;
import mss.mooncake.commerce.dto.response.FinanceResponse;
import mss.mooncake.commerce.exception.FinanceException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceRepositoryImpl implements FinanceRepository {
    private static final SecureRandom ORDER_CODE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public FinanceRepositoryImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    private static Timestamp utc(Instant value) { return FinanceRepository.utc(value); }

    private final RowMapper<Context> contexts = (rs, n) -> new Context(rs.getLong("seller_order_id"),
            rs.getLong("customer_id"), rs.getLong("supplier_id"), rs.getLong("total_amount"),
            rs.getObject("deposit_amount", Long.class), rs.getObject("delivery_date", LocalDate.class),
            rs.getBoolean("accepted"), rs.getLong("source_version"), rs.getLong("fulfillment_version"),
            rs.getBoolean("delivered"), rs.getBoolean("received"));

    private final RowMapper<Payment> payments = (rs, n) -> new Payment(rs.getLong("payment_id"),
            rs.getLong("seller_order_id"), rs.getString("payment_type"), rs.getBigDecimal("amount").longValueExact(),
            rs.getString("status"), rs.getString("hold_status"), rs.getObject("order_code", Long.class),
            rs.getString("payment_link_id"), rs.getString("idempotency_key"), instant(rs, "due_at"),
            instant(rs, "expires_at"), rs.getString("checkout_url"), rs.getString("qr_code"));

    private final RowMapper<CustomerPayment> customerPayments = (rs, n) -> new CustomerPayment(
            rs.getLong("customer_payment_id"), rs.getLong("customer_order_id"), rs.getLong("order_code"),
            rs.getString("idempotency_key"), rs.getLong("amount"), rs.getString("status"),
            rs.getString("payment_link_id"), rs.getString("checkout_url"), rs.getString("qr_code"),
            instant(rs, "expires_at"));

    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    public long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    public long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) { statement.setObject(i + 1, args[i]); }
            return statement;
        }, keys);
        return ((Number) keys.getKeyList().getFirst().values().stream()
                .filter(Number.class::isInstance).findFirst().orElseThrow()).longValue();
    }

    public Context context(long orderId) {
        var found = jdbc.query("SELECT * FROM finance_context WHERE seller_order_id=?", contexts, orderId);
        if (found.isEmpty()) { throw new FinanceException(HttpStatus.CONFLICT, "PAYMENT_CONTEXT_NOT_READY"); }
        return found.getFirst();
    }

    /** Update obtains an exclusive aggregate row lock on SQL Server and in integration tests. */
    public Context lock(long orderId) {
        lockCustomerOrder(parentOrderId(orderId));
        update("UPDATE seller_order SET status=status WHERE seller_order_id=?", orderId);
        update("UPDATE finance_context SET version=version WHERE seller_order_id=?", orderId);
        return context(orderId);
    }

    public long parentOrderId(long sellerOrderId) {
        var found = jdbc.queryForList("SELECT order_id FROM seller_order WHERE seller_order_id=?", Long.class,
                sellerOrderId);
        if (found.isEmpty()) { throw new FinanceException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"); }
        return found.getFirst();
    }

    public List<String> sellerStatuses(long orderId) {
        return jdbc.queryForList("SELECT status FROM seller_order WHERE order_id=? ORDER BY seller_order_id",
                String.class, orderId);
    }

    public List<Long> pendingCancellationOrderIds() {
        return jdbc.queryForList("SELECT TOP 50 order_id FROM customer_order WHERE status='CANCEL_REQUESTED' ORDER BY order_id",
                Long.class);
    }

    /** Unpaid RFQ orders placed at or before the cutoff; an approved case is never retried, a rejected one is history. */
    public List<Long> overdueRfqOrderIds(Instant placedBefore) {
        return jdbc.queryForList("""
                SELECT TOP 50 o.order_id FROM customer_order o
                WHERE o.source_type='RFQ' AND o.status IN ('AWAITING_DEPOSIT','AWAITING_PAYMENT') AND o.placed_at<=?
                AND NOT EXISTS (SELECT 1 FROM seller_order s JOIN cancellation c ON c.seller_order_id=s.seller_order_id
                WHERE s.order_id=o.order_id AND c.status='APPROVED')
                ORDER BY o.order_id
                """, Long.class, utc(placedBefore));
    }

    /** Unpaid retail checkouts placed at or before the cutoff that still hold confirmed Catalog stock. */
    public List<Long> overdueRetailOrderIds(Instant placedBefore) {
        return jdbc.queryForList("""
                SELECT TOP 50 o.order_id FROM customer_order o
                WHERE o.source_type='RETAIL' AND o.status IN ('AWAITING_PAYMENT','PENDING_CONFIRMATION')
                AND o.placed_at<=? AND o.stock_reservation_key IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM seller_order s JOIN cancellation c ON c.seller_order_id=s.seller_order_id
                WHERE s.order_id=o.order_id AND c.status='APPROVED')
                ORDER BY o.order_id
                """, Long.class, utc(placedBefore));
    }

    public List<Long> autoAcceptableSellerOrderIds(Instant deliveredBefore) {
        return jdbc.queryForList("""
                SELECT TOP 50 s.seller_order_id FROM seller_order s
                JOIN finance_context f ON f.seller_order_id=s.seller_order_id
                JOIN (SELECT seller_order_id, MAX(delivered_at) AS last_delivered FROM shipment
                      GROUP BY seller_order_id) d ON d.seller_order_id=s.seller_order_id
                WHERE s.status='DELIVERED' AND f.delivered=1 AND f.received=0
                AND d.last_delivered IS NOT NULL AND d.last_delivered<=?
                AND NOT EXISTS (SELECT 1 FROM dispute x WHERE x.seller_order_id=s.seller_order_id
                    AND (x.status IN ('OPEN','ADMIN_REVIEW','REFUND_PENDING') OR x.opened_at>=d.last_delivered))
                ORDER BY s.seller_order_id
                """, Long.class, utc(deliveredBefore));
    }

    /** Retail checkouts whose confirmed stock (whole checkout, or a cancelled seller order's lines) awaits Catalog. */
    public List<Long> unrestoredCancelledRetailOrderIds() {
        return jdbc.queryForList("""
                SELECT TOP 50 o.order_id FROM customer_order o WHERE o.source_type='RETAIL'
                AND o.stock_reservation_key IS NOT NULL AND o.stock_restored_at IS NULL
                AND (o.status='CANCELLED' OR EXISTS (SELECT 1 FROM seller_order s WHERE s.order_id=o.order_id
                    AND s.status='CANCELLED' AND s.stock_restored_at IS NULL))
                ORDER BY o.order_id
                """, Long.class);
    }

    public List<Long> unrestoredCancelledSellerOrderIds(long orderId) {
        return jdbc.queryForList("""
                SELECT seller_order_id FROM seller_order WHERE order_id=? AND status='CANCELLED'
                AND stock_restored_at IS NULL ORDER BY seller_order_id
                """, Long.class, orderId);
    }

    public List<StockLine> sellerOrderStockLines(long sellerOrderId) {
        return jdbc.query("""
                SELECT variant_id_ref, SUM(quantity) AS quantity FROM order_item
                WHERE seller_order_id=? AND variant_id_ref IS NOT NULL
                GROUP BY variant_id_ref ORDER BY variant_id_ref
                """, (rs, n) -> new StockLine(rs.getLong("variant_id_ref"), rs.getInt("quantity")), sellerOrderId);
    }

    public boolean hasContext(long orderId) {
        return count("SELECT COUNT(*) FROM finance_context WHERE seller_order_id=?", orderId) > 0;
    }

    public boolean matchesPersistedOrder(FinanceOrderContext c) {
        return count("""
                SELECT COUNT(*) FROM seller_order s JOIN customer_order o ON o.order_id=s.order_id
                WHERE s.seller_order_id=? AND o.customer_id_ref=? AND s.supplier_id_ref=? AND s.total_amount=?
                """, c.sellerOrderId(), c.customerId(), c.supplierId(), c.totalAmount()) == 1;
    }

    public Payment payment(long id) {
        var found = jdbc.query("SELECT * FROM payment WHERE payment_id=?", payments, id);
        if (found.isEmpty()) { throw new FinanceException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"); }
        return found.getFirst();
    }

    public Payment byOrderCode(long code) {
        var found = jdbc.query("SELECT * FROM payment WHERE order_code=?", payments, code);
        if (found.isEmpty()) { throw new FinanceException(HttpStatus.BAD_REQUEST, "UNKNOWN_ORDER_CODE"); }
        return found.getFirst();
    }

    public List<Payment> payments(long orderId) {
        return jdbc.query("SELECT * FROM payment WHERE seller_order_id=? ORDER BY payment_id", payments, orderId);
    }

    public long paid(long orderId) {
        return count("SELECT COALESCE(SUM(amount),0) FROM payment WHERE seller_order_id=? AND status='SUCCESS'", orderId);
    }

    public long nextOrderCode() {
        // Separate databases may share one payOS channel. A local SQL sequence would restart
        // after a fresh Docker volume and reuse a code already registered with payOS.
        for (int attempt = 0; attempt < 16; attempt++) {
            long code = ORDER_CODE_RANDOM.nextLong(100_000_000_000_000L, 1_000_000_000_000_000L);
            if (count("SELECT COUNT(*) FROM payment WHERE order_code=?", code) == 0
                    && count("SELECT COUNT(*) FROM customer_payment WHERE order_code=?", code) == 0) {
                return code;
            }
        }
        throw new FinanceException(HttpStatus.CONFLICT, "ORDER_CODE_UNAVAILABLE");
    }

    public String customerOrderNumber(long orderId) {
        return jdbc.queryForObject("SELECT order_no FROM customer_order WHERE order_id=?", String.class, orderId);
    }

    @Override
    public Long sellerOrderIdByNumber(String sellerOrderNo) {
        return jdbc.query("SELECT seller_order_id FROM seller_order WHERE seller_order_no=?",
                (rs, row) -> rs.getLong(1), sellerOrderNo).stream().findFirst().orElse(null);
    }

    @Override
    public java.util.Map<Long, Long> reservedRefundAmounts(long sellerOrderId) {
        return jdbc.query("""
                SELECT p.payment_id, SUM(r.amount) AS reserved
                FROM payment p JOIN refund r ON r.payment_id=p.payment_id
                WHERE p.seller_order_id=? AND r.status<>'REJECTED' GROUP BY p.payment_id
                """, (org.springframework.jdbc.core.ResultSetExtractor<java.util.Map<Long, Long>>) rs -> {
            var amounts = new java.util.HashMap<Long, Long>();
            while (rs.next()) amounts.put(rs.getLong(1), rs.getBigDecimal(2).longValueExact());
            return amounts;
        }, sellerOrderId);
    }

    public String sellerOrderNumber(long sellerOrderId) {
        return jdbc.queryForObject("SELECT seller_order_no FROM seller_order WHERE seller_order_id=?", String.class,
                sellerOrderId);
    }

    public boolean active(long orderId) {
        return count("""
                SELECT COUNT(*) FROM payment WHERE seller_order_id=? AND status IN
                ('CREATING','PENDING','RECONCILING','CANCELLING') AND order_code IS NOT NULL
                """, orderId) > 0;
    }

    public void lockCustomerOrder(long orderId) {
        if (update("UPDATE customer_order SET status=status WHERE order_id=?", orderId) != 1) {
            throw new FinanceException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        }
    }

    public long customerOrderOwner(long orderId) {
        return count("SELECT customer_id_ref FROM customer_order WHERE order_id=?", orderId);
    }

    public long customerOrderTotal(long orderId) {
        return jdbc.queryForObject("SELECT grand_total FROM customer_order WHERE order_id=?", java.math.BigDecimal.class,
                orderId).longValueExact();
    }

    public String stockReservationKey(long orderId) {
        return jdbc.queryForObject("SELECT stock_reservation_key FROM customer_order WHERE order_id=?", String.class, orderId);
    }

    public boolean retailOrder(long orderId) {
        return count("SELECT COUNT(*) FROM customer_order WHERE order_id=? AND source_type='RETAIL'", orderId) == 1;
    }

    public List<Long> sellerOrderIds(long orderId) {
        return jdbc.queryForList("SELECT seller_order_id FROM seller_order WHERE order_id=? ORDER BY seller_order_id",
                Long.class, orderId);
    }

    public CustomerPayment customerPayment(long id) {
        var found = jdbc.query("SELECT * FROM customer_payment WHERE customer_payment_id=?", customerPayments, id);
        if (found.isEmpty()) { throw new FinanceException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"); }
        return found.getFirst();
    }

    public List<CustomerPayment> customerPayments(long orderId) {
        return jdbc.query("SELECT * FROM customer_payment WHERE customer_order_id=? ORDER BY customer_payment_id DESC",
                customerPayments, orderId);
    }

    public List<CustomerPayment> customerPaymentByCode(long code) {
        return jdbc.query("SELECT * FROM customer_payment WHERE order_code=?", customerPayments, code);
    }

    public List<CustomerPaymentItem> customerPaymentItems(long id) {
        return jdbc.query("""
                SELECT i.payment_id,p.seller_order_id,i.amount,s.seller_order_no
                FROM customer_payment_item i JOIN payment p ON p.payment_id=i.payment_id
                JOIN seller_order s ON s.seller_order_id=p.seller_order_id
                WHERE i.customer_payment_id=? ORDER BY p.seller_order_id
                """, (rs, n) -> new CustomerPaymentItem(rs.getLong("payment_id"),
                        rs.getLong("seller_order_id"), rs.getLong("amount"), rs.getString("seller_order_no")), id);
    }

    public boolean activeCustomerPayment(long sellerOrderId) {
        return count("""
                SELECT COUNT(*) FROM customer_payment_item i
                JOIN customer_payment c ON c.customer_payment_id=i.customer_payment_id
                JOIN payment p ON p.payment_id=i.payment_id
                WHERE p.seller_order_id=? AND c.status IN ('CREATING','RECONCILING','PENDING','CANCELLING')
                """, sellerOrderId) > 0;
    }

    public List<FinanceResponse.Case> cancellations(long orderId) {
        return jdbc.query("SELECT * FROM cancellation WHERE seller_order_id=? ORDER BY cancellation_id",
                (rs, n) -> new FinanceResponse.Case(rs.getLong("cancellation_id"), rs.getString("status"),
                        rs.getString("reason"), null, null, rs.getString("decision_reason")), orderId);
    }

    public List<FinanceResponse.Case> refunds(long orderId) {
        return jdbc.query("""
                SELECT r.* FROM refund r JOIN payment p ON p.payment_id=r.payment_id
                WHERE p.seller_order_id=? ORDER BY r.refund_id
                """, (rs, n) -> new FinanceResponse.Case(rs.getLong("refund_id"), rs.getString("status"),
                        rs.getString("reason"), rs.getBigDecimal("amount").longValueExact(),
                        rs.getString("evidence_url"), rs.getString("policy_snapshot"), null, null,
                        null, null, null, rs.getObject("dispute_id", Long.class)), orderId);
    }

    public List<FinanceResponse.Case> disputes(long orderId) {
        return jdbc.query("SELECT * FROM dispute WHERE seller_order_id=? ORDER BY dispute_id",
                (rs, n) -> new FinanceResponse.Case(rs.getLong("dispute_id"), rs.getString("status"),
                        rs.getString("reason"), rs.getObject("awarded_amount") == null ? null
                                : rs.getBigDecimal("awarded_amount").longValueExact(),
                        rs.getString("evidence_url"), rs.getString("resolution"), rs.getString("seller_response"),
                        instant(rs, "opened_at"), instant(rs, "responded_at"), instant(rs, "escalated_at"),
                        rs.getString("decision_type"), null), orderId);
    }

    public Instant deliveredAt(long orderId) {
        return jdbc.queryForObject("SELECT MAX(delivered_at) FROM shipment WHERE seller_order_id=?",
                (rs, n) -> instant(rs, 1), orderId);
    }

    public Instant lastPaidAt(long orderId) {
        return jdbc.queryForObject("SELECT MAX(paid_at) FROM payment WHERE seller_order_id=? AND status='SUCCESS'",
                (rs, n) -> instant(rs, 1), orderId);
    }

    public Instant expectedDeliveryAt(long orderId) {
        return jdbc.queryForObject("""
                SELECT MAX(expected_delivery_at) FROM shipment WHERE seller_order_id=? AND status<>'CANCELLED'
                """, (rs, n) -> instant(rs, 1), orderId);
    }

    public long refunded(long orderId) {
        return count("SELECT COALESCE(SUM(r.amount),0) FROM refund r JOIN payment p ON p.payment_id=r.payment_id "
                + "WHERE p.seller_order_id=? AND r.status='SUCCESS'", orderId);
    }

    public long refundedForDispute(long disputeId) {
        return count("SELECT COALESCE(SUM(amount),0) FROM refund WHERE dispute_id=? AND status='SUCCESS'", disputeId);
    }

    public List<Long> pendingDisputeOrderIds(int offset, int limit) {
        return jdbc.queryForList("SELECT DISTINCT seller_order_id FROM dispute "
                + "WHERE status IN ('OPEN','ADMIN_REVIEW','REFUND_PENDING') "
                + "ORDER BY seller_order_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", Long.class, offset, limit);
    }

    public List<FinanceResponse.Settlement> settlements(long orderId) {
        return jdbc.query("SELECT * FROM settlement WHERE seller_order_id=? ORDER BY settlement_id",
                (rs, n) -> new FinanceResponse.Settlement(rs.getLong("settlement_id"),
                        rs.getBigDecimal("gross_amount").longValueExact(), rs.getString("fee_basis"),
                        rs.getString("rounding_rule"), rs.getBigDecimal("fee_amount").longValueExact(),
                        rs.getBigDecimal("supplier_payout_amount").longValueExact(), rs.getString("status"),
                        rs.getBigDecimal("refunded_amount").longValueExact()), orderId);
    }

    public List<TransferDestination> transferDestinations(long orderId) {
        return jdbc.query("""
                SELECT 'REFUND' AS kind,r.refund_id AS transfer_id,r.amount,r.status,r.transfer_reference,
                       r.bank_bin,r.bank_name,r.bank_account_number,r.bank_account_holder
                FROM refund r JOIN payment p ON p.payment_id=r.payment_id
                WHERE p.seller_order_id=? AND r.status='MANUAL_REVIEW'
                UNION ALL
                SELECT 'PAYOUT',s.settlement_id,s.supplier_payout_amount,s.status,s.payout_reference,
                       s.bank_bin,s.bank_name,s.bank_account_number,s.bank_account_holder
                FROM settlement s WHERE s.seller_order_id=? AND s.status='PROCESSING'
                """, (rs, n) -> new TransferDestination(rs.getString("kind"), rs.getLong("transfer_id"),
                        rs.getBigDecimal("amount").longValueExact(), rs.getString("status"),
                        rs.getString("transfer_reference"), rs.getString("bank_bin"), rs.getString("bank_name"),
                        rs.getString("bank_account_number"), rs.getString("bank_account_holder")), orderId, orderId);
    }

    public List<SupplierPayout> supplierPayouts(long supplierId) {
        return jdbc.query("""
                SELECT s.seller_order_id,s.seller_order_no,s.order_id,o.order_no,s.store_name_snapshot,s.status,o.placed_at,
                t.settlement_id,t.status AS settlement_status,t.fee_amount,t.supplier_payout_amount,t.refunded_amount,
                t.payout_reference,t.settled_at
                FROM seller_order s JOIN customer_order o ON o.order_id=s.order_id
                JOIN finance_context f ON f.seller_order_id=s.seller_order_id
                LEFT JOIN settlement t ON t.seller_order_id=s.seller_order_id
                WHERE s.supplier_id_ref=? ORDER BY s.seller_order_id DESC
                """, (rs, n) -> new SupplierPayout(rs.getLong("seller_order_id"), rs.getString("seller_order_no"),
                        rs.getLong("order_id"), rs.getString("order_no"), rs.getString("store_name_snapshot"),
                        rs.getString("status"), instant(rs, "placed_at"), rs.getObject("settlement_id", Long.class),
                        rs.getString("settlement_status"), exact(rs.getBigDecimal("fee_amount")),
                        exact(rs.getBigDecimal("supplier_payout_amount")), exact(rs.getBigDecimal("refunded_amount")),
                        rs.getString("payout_reference"), instant(rs, "settled_at")), supplierId);
    }

    private static Long exact(java.math.BigDecimal amount) { return amount == null ? null : amount.longValueExact(); }

    public List<FinanceResponse.Audit> history(long orderId) {
        return jdbc.query("SELECT TOP 200 * FROM finance_audit WHERE seller_order_id=? ORDER BY audit_id DESC",
                (rs, n) -> new FinanceResponse.Audit(rs.getLong("audit_id"), rs.getString("event_type"),
                        rs.getLong("resource_id"),
                        rs.getString("actor"), rs.getString("detail"), instant(rs, "created_at")), orderId);
    }

    public void event(long orderId, String type, long resourceId, String actor, String detail) {
        var now = utc(Instant.now());
        update("UPDATE finance_context SET version=version+1 WHERE seller_order_id=?", orderId);
        long version = count("SELECT version FROM finance_context WHERE seller_order_id=?", orderId);
        update("INSERT INTO finance_audit(seller_order_id,event_type,resource_id,actor,detail,created_at) VALUES(?,?,?,?,?,?)",
                orderId, type, resourceId, actor, detail, now);
        update("""
                INSERT INTO finance_outbox(event_id,seller_order_id,aggregate_version,event_type,resource_id,created_at)
                VALUES(?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), orderId, version, type, resourceId, now);
    }

    public void ledger(long orderId, String key, String type, long amount) {
        update("INSERT INTO finance_ledger(seller_order_id,entry_key,entry_type,amount,created_at) VALUES(?,?,?,?,?)",
                orderId, key, type, amount, utc(Instant.now()));
    }

    public List<FinanceEvent> pendingEvents() {
        return jdbc.query("""
                SELECT TOP 100 * FROM finance_outbox WHERE published_at IS NULL
                ORDER BY seller_order_id,aggregate_version
                """, (rs, n) -> new FinanceEvent(
                        rs.getString("event_id"), rs.getLong("seller_order_id"), rs.getLong("aggregate_version"),
                        rs.getString("event_type"), rs.getLong("resource_id")));
    }
}
