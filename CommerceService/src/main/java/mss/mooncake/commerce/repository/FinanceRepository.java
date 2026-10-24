package mss.mooncake.commerce.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
public class FinanceRepository {
    public record Context(long id, long customerId, long supplierId, long total, Long deposit,
            LocalDate deliveryDate, boolean accepted, long sourceVersion, long fulfillmentVersion,
            boolean delivered, boolean received) { }
    public record Payment(long id, long orderId, String type, long amount, String status,
            String hold, Long orderCode, String linkId, String key, Instant dueAt,
            Instant expiresAt, String checkoutUrl, String qrCode) {
        public FinanceResponse.Payment response() {
            return new FinanceResponse.Payment(id, orderId, type, amount, status, hold, dueAt,
                    expiresAt, checkoutUrl, qrCode);
        }
    }
    public record CustomerPayment(long id, long orderId, long orderCode, String key, long amount,
            String status, String linkId, String checkoutUrl, String qrCode, Instant expiresAt) { }
    public record CustomerPaymentItem(long paymentId, long sellerOrderId, long amount, String sellerOrderNo) { }

    private final JdbcTemplate jdbc;

    public FinanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }

    public static Timestamp utc(Instant value) {
        return value == null ? null : Timestamp.valueOf(java.time.LocalDateTime.ofInstant(value, java.time.ZoneOffset.UTC));
    }

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
        update("UPDATE finance_context SET version=version WHERE seller_order_id=?", orderId);
        return context(orderId);
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
        return jdbc.queryForObject("SELECT NEXT VALUE FOR finance_order_code", Long.class);
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
                        rs.getString("evidence_url"), rs.getString("policy_snapshot")), orderId);
    }

    public List<FinanceResponse.Case> disputes(long orderId) {
        return jdbc.query("SELECT * FROM dispute WHERE seller_order_id=? ORDER BY dispute_id",
                (rs, n) -> new FinanceResponse.Case(rs.getLong("dispute_id"), rs.getString("status"),
                        rs.getString("reason"), null, rs.getString("evidence_url"), rs.getString("resolution")), orderId);
    }

    public List<FinanceResponse.Settlement> settlements(long orderId) {
        return jdbc.query("SELECT * FROM settlement WHERE seller_order_id=? ORDER BY settlement_id",
                (rs, n) -> new FinanceResponse.Settlement(rs.getLong("settlement_id"),
                        rs.getBigDecimal("gross_amount").longValueExact(), rs.getString("fee_basis"),
                        rs.getString("rounding_rule"), rs.getBigDecimal("fee_amount").longValueExact(),
                        rs.getBigDecimal("supplier_payout_amount").longValueExact(), rs.getString("status")), orderId);
    }

    public List<FinanceResponse.Audit> history(long orderId) {
        return jdbc.query("SELECT TOP 200 * FROM finance_audit WHERE seller_order_id=? ORDER BY audit_id DESC",
                (rs, n) -> new FinanceResponse.Audit(rs.getLong("audit_id"), rs.getString("event_type"),
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
