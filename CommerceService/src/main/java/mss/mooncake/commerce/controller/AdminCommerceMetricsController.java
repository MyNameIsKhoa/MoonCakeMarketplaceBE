package mss.mooncake.commerce.controller;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/commerce")
public class AdminCommerceMetricsController {
    public record Metrics(long orders, BigDecimal collectedThisMonth, String month) { }

    private final JdbcTemplate db;

    public AdminCommerceMetricsController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/metrics")
    public Metrics metrics() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Long orders = db.queryForObject("SELECT COUNT(*) FROM customer_order", Long.class);
        // Retail child payment rows are allocations of customer_payment, not extra collections.
        BigDecimal collected = db.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) FROM (
                    SELECT amount FROM customer_payment WHERE status='SUCCESS' AND paid_at >= ? AND paid_at < ?
                    UNION ALL
                    SELECT p.amount FROM payment p WHERE p.status='SUCCESS' AND p.paid_at >= ? AND p.paid_at < ?
                    AND NOT EXISTS (SELECT 1 FROM customer_payment_item i WHERE i.payment_id=p.payment_id)
                ) receipts
                """, BigDecimal.class, Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to));
        return new Metrics(orders == null ? 0 : orders, collected == null ? BigDecimal.ZERO : collected, month.toString());
    }
}
