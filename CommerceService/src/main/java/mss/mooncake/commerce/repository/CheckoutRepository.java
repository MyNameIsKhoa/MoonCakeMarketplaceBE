package mss.mooncake.commerce.repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import mss.mooncake.commerce.client.dto.identity.AddressSummaryResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.service.OrderNumberFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CheckoutRepository {
    public record Attempt(long id, long customerId, long cartId, long addressId, String key,
                          String fingerprint, String reservationKey, LocalDateTime expiresAt,
                          String status, Long orderId) { }
    public record Store(CartPricingResponse.StorePrice price, BigDecimal fee, String supplierName) { }

    private final JdbcTemplate db;
    private final OrderNumberFormatter numbers;
    private final FinanceIntegrationService finance;

    public CheckoutRepository(JdbcTemplate db, OrderNumberFormatter numbers, FinanceIntegrationService finance) {
        this.db = db;
        this.numbers = numbers;
        this.finance = finance;
    }

    public Attempt byKey(long customerId, String key) {
        return db.query("SELECT * FROM checkout_attempt WHERE customer_id_ref=? AND idempotency_key=?",
                (rs, n) -> new Attempt(rs.getLong("attempt_id"), rs.getLong("customer_id_ref"),
                        rs.getLong("cart_id"), rs.getLong("address_id_ref"), rs.getString("idempotency_key"),
                        rs.getString("cart_fingerprint"), rs.getString("reservation_key"),
                        rs.getTimestamp("reservation_expires_at").toLocalDateTime(), rs.getString("status"),
                        rs.getObject("order_id", Long.class)), customerId, key).stream().findFirst().orElse(null);
    }

    public List<Attempt> pending() {
        return db.query("SELECT * FROM checkout_attempt WHERE status='PENDING_STOCK'",
                (rs, n) -> new Attempt(rs.getLong("attempt_id"), rs.getLong("customer_id_ref"),
                        rs.getLong("cart_id"), rs.getLong("address_id_ref"), rs.getString("idempotency_key"),
                        rs.getString("cart_fingerprint"), rs.getString("reservation_key"),
                        rs.getTimestamp("reservation_expires_at").toLocalDateTime(), rs.getString("status"),
                        rs.getObject("order_id", Long.class)));
    }

    public void purgeExpiredKeys() {
        db.update("DELETE FROM checkout_attempt WHERE status IN ('COMPLETED','FAILED') "
                + "AND updated_at < DATEADD(MINUTE,-30,SYSUTCDATETIME())");
    }

    @Transactional
    public Attempt start(long customerId, long cartId, long addressId, String key, String fingerprint) {
        String reservationKey = "checkout-" + UUID.randomUUID();
        LocalDateTime expiry = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(15).withNano(0);
        if (db.update("UPDATE cart SET updated_at=updated_at WHERE cart_id=? AND customer_id_ref=? AND status='ACTIVE'",
                cartId, customerId) != 1) throw new IllegalStateException("Cart is not active");
        if (!fingerprint.equals(cartFingerprint(cartId)))
            throw new IllegalStateException("Cart changed before checkout attempt");
        db.update("""
                INSERT INTO checkout_attempt(customer_id_ref,cart_id,address_id_ref,idempotency_key,
                cart_fingerprint,reservation_key,reservation_expires_at,status)
                VALUES(?,?,?,?,?,?,?,'CREATED')
                """, customerId, cartId, addressId, key, fingerprint, reservationKey, expiry);
        return byKey(customerId, key);
    }

    @Transactional
    public boolean failCreated(Attempt attempt) {
        return db.update("UPDATE checkout_attempt SET status='FAILED',updated_at=SYSUTCDATETIME() WHERE attempt_id=? AND status='CREATED'",
                attempt.id()) == 1;
    }

    @Transactional
    public void persistPending(Attempt attempt, AddressSummaryResponse address, List<Store> stores,
                               String fingerprint) {
        if (db.update("UPDATE checkout_attempt SET status=status WHERE attempt_id=? AND status='CREATED'",
                attempt.id()) != 1) throw new IllegalStateException("Checkout attempt already finalized");
        if (db.update("UPDATE cart SET status='CHECKOUT_PENDING',updated_at=SYSUTCDATETIME() "
                + "WHERE cart_id=? AND customer_id_ref=? AND status='ACTIVE'", attempt.cartId(),
                attempt.customerId()) != 1) throw new IllegalStateException("Cart changed during checkout");
        if (!fingerprint.equals(cartFingerprint(attempt.cartId())))
            throw new IllegalStateException("Cart items changed during checkout");
        BigDecimal subtotal = stores.stream().map(s -> s.price().subtotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = stores.stream().map(s -> s.price().discountTotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shipping = stores.stream().map(Store::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = stores.stream().map(s -> s.price().totalAmount().add(s.fee()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long orderId = insert("""
                INSERT INTO customer_order(order_no,customer_id_ref,source_type,subtotal,discount_total,
                shipping_total,grand_total,status,placed_at) VALUES(?,?,'RETAIL',?,?,?,?,?,?)
                """, "TEMP-" + UUID.randomUUID(), attempt.customerId(), subtotal, discount, shipping,
                total, "PENDING_STOCK", now);
        db.update("UPDATE customer_order SET order_no=? WHERE order_id=?",
                numbers.customerOrderNo(now.toLocalDate(), orderId), orderId);
        db.update("""
                INSERT INTO delivery_address(order_id,source_address_id_ref,recipient_name,recipient_phone,
                address_line,ward,district,city) VALUES(?,?,?,?,?,?,?,?)
                """, orderId, address.addressId(), address.recipientName(), address.recipientPhone(),
                address.addressLine(), address.ward(), address.district(), address.city());
        for (Store store : stores) {
            var price = store.price();
            long sellerId = insert("""
                    INSERT INTO seller_order(order_id,seller_order_no,store_id_ref,supplier_id_ref,
                    store_name_snapshot,supplier_name_snapshot,subtotal,shipping_fee,total_amount,status)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, orderId, "TEMP-" + UUID.randomUUID(), price.storeId(), price.supplierId(),
                    price.storeName(), store.supplierName(), price.subtotal(), store.fee(),
                    price.totalAmount().add(store.fee()), "PENDING_STOCK");
            db.update("UPDATE seller_order SET seller_order_no=? WHERE seller_order_id=?",
                    numbers.sellerOrderNo(now.toLocalDate(), orderId, sellerId), sellerId);
            for (var line : price.items()) {
                db.update("""
                        INSERT INTO order_item(seller_order_id,variant_id_ref,product_name_snapshot,
                        sku_snapshot,variant_name_snapshot,image_url_snapshot,quantity,unit_price,
                        discount_amount,subtotal) VALUES(?,?,?,?,?,?,?,?,?,?)
                        """, sellerId, line.variantId(), line.productName(), line.sku(), line.variantName(),
                        line.imageUrl(), line.quantity(), line.unitPrice(), line.discountAmount(), line.subtotal());
            }
        }
        db.update("UPDATE checkout_attempt SET status='PENDING_STOCK',order_id=?,updated_at=SYSUTCDATETIME() "
                + "WHERE attempt_id=? AND status='CREATED'", orderId, attempt.id());
    }

    @Transactional
    public void complete(Attempt attempt) {
        if (db.update("UPDATE checkout_attempt SET status=status WHERE attempt_id=? AND status='PENDING_STOCK'",
                attempt.id()) != 1) return;
        var sellers = db.query("""
                SELECT seller_order_id,supplier_id_ref,total_amount FROM seller_order WHERE order_id=?
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2),
                        rs.getBigDecimal(3).longValueExact()}, attempt.orderId());
        for (long[] seller : sellers) {
            finance.syncOrder(new FinanceOrderContext(seller[0], attempt.customerId(), seller[1],
                    seller[2], null, null, true, 1));
        }
        db.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE order_id=?", attempt.orderId());
        db.update("UPDATE customer_order SET status='AWAITING_PAYMENT' WHERE order_id=?", attempt.orderId());
        db.update("UPDATE cart SET status='CHECKED_OUT' WHERE cart_id=?", attempt.cartId());
        db.update("UPDATE checkout_attempt SET status='COMPLETED',updated_at=SYSUTCDATETIME() WHERE attempt_id=?",
                attempt.id());
    }

    @Transactional
    public void expire(Attempt attempt) {
        if (db.update("UPDATE checkout_attempt SET status='FAILED',updated_at=SYSUTCDATETIME() "
                + "WHERE attempt_id=? AND status='PENDING_STOCK'", attempt.id()) != 1) return;
        db.update("UPDATE seller_order SET status='CANCELLED' WHERE order_id=?", attempt.orderId());
        db.update("UPDATE customer_order SET status='CANCELLED' WHERE order_id=?", attempt.orderId());
        db.update("UPDATE cart SET status='ACTIVE' WHERE cart_id=? AND status='CHECKOUT_PENDING'", attempt.cartId());
    }

    public RetailCheckoutResponse response(long orderId) {
        return db.queryForObject("SELECT order_id,order_no,status,grand_total FROM customer_order WHERE order_id=?",
                (rs, n) -> new RetailCheckoutResponse(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), db.query("""
                                SELECT seller_order_id,seller_order_no,total_amount FROM seller_order
                                WHERE order_id=? ORDER BY seller_order_id
                                """, (line, index) -> new RetailCheckoutResponse.Seller(line.getLong(1),
                                line.getString(2), line.getBigDecimal(3)), orderId)), orderId);
    }

    public String cartFingerprint(long cartId) {
        String contents = db.query("SELECT variant_id_ref,quantity FROM cart_item WHERE cart_id=? ORDER BY variant_id_ref",
                (rs, n) -> rs.getLong(1) + ":" + rs.getInt(2) + ";", cartId).stream()
                .reduce("", String::concat);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contents.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Missing generated database ID");
        return key.getKey().longValue();
    }
}
