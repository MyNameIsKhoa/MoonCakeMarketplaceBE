package mss.mooncake.commerce.controller;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/commerce")
public class AdminCancellationController {
    /**
     * paidAmount: payments collected. refundedAmount: refunds confirmed with transfer proof.
     * pendingRefundAmount: refunds created and still waiting for the admin's transfer.
     * retainedAmount: deposit kept for the supplier (buyer cancelled after preparation started); not refunded.
     */
    public record PendingCancellation(long cancellationId, long sellerOrderId, String sellerOrderNo,
                                      String customerOrderNo, String sourceType, long paidAmount,
                                      long refundedAmount, long pendingRefundAmount, long retainedAmount,
                                      Long activePaymentId, String reason, LocalDateTime requestedAt) { }

    private final JdbcTemplate db;

    public AdminCancellationController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/cancellations")
    public List<PendingCancellation> pending() {
        return db.query("""
                SELECT TOP (100) c.cancellation_id, c.seller_order_id, s.seller_order_no,
                       o.order_no, o.source_type,
                       COALESCE((SELECT SUM(p.amount) FROM payment p WHERE p.seller_order_id=s.seller_order_id AND p.status='SUCCESS'),0),
                       COALESCE((SELECT SUM(r.amount) FROM refund r JOIN payment p ON p.payment_id=r.payment_id
                                 WHERE p.seller_order_id=s.seller_order_id AND r.status='SUCCESS'),0),
                       COALESCE((SELECT SUM(r.amount) FROM refund r JOIN payment p ON p.payment_id=r.payment_id
                                 WHERE p.seller_order_id=s.seller_order_id AND r.status='MANUAL_REVIEW'),0),
                       c.retained_amount,
                       (SELECT TOP (1) p.payment_id FROM payment p WHERE p.seller_order_id=s.seller_order_id
                        AND p.order_code IS NOT NULL AND p.status IN ('CREATING','PENDING','RECONCILING','CANCELLING')
                        ORDER BY p.payment_id DESC),
                       c.reason, c.requested_at
                FROM cancellation c
                JOIN seller_order s ON s.seller_order_id=c.seller_order_id
                JOIN customer_order o ON o.order_id=s.order_id
                WHERE c.status='REQUESTED'
                ORDER BY c.requested_at ASC, c.cancellation_id ASC
        """, (rs, row) -> new PendingCancellation(rs.getLong(1), rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6).longValueExact(),
                rs.getBigDecimal(7).longValueExact(), rs.getBigDecimal(8).longValueExact(), rs.getLong(9),
                rs.getObject(10) == null ? null : rs.getLong(10), rs.getString(11),
                rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toLocalDateTime()));
    }
}
