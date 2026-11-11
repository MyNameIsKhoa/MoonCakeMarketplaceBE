package mss.mooncake.commerce.controller;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/commerce")
public class AdminCommerceMetricsController {
    public record KeyCount(String key, long count) { }
    public record DailyPoint(LocalDate date, BigDecimal collected, long retailOrders, long rfqOrders) { }
    /** orders, collectedThisMonth and ordersByStatus are all-time / current-month; daily covers the last {@code days} UTC days. */
    public record Metrics(long orders, BigDecimal collectedThisMonth, String month, int days,
                          List<KeyCount> ordersByStatus, List<DailyPoint> daily) { }

    private final JdbcTemplate db;

    public AdminCommerceMetricsController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/metrics")
    public Metrics metrics(@RequestParam(defaultValue = "30") int days) {
        int span = Math.clamp(days, 1, 90);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(span - 1);
        LocalDate end = today.plusDays(1);
        YearMonth month = YearMonth.from(today);

        Long orders = db.queryForObject("SELECT COUNT(*) FROM customer_order", Long.class);
        BigDecimal collectedThisMonth = collectedByDay(month.atDay(1), month.plusMonths(1).atDay(1)).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<KeyCount> byStatus = db.query("SELECT status, COUNT(*) AS n FROM customer_order GROUP BY status ORDER BY n DESC",
                (rs, i) -> new KeyCount(rs.getString("status"), rs.getLong("n")));

        Map<LocalDate, BigDecimal> collected = collectedByDay(from, end);
        Map<LocalDate, long[]> placed = new HashMap<>(); // [retail, rfq]
        db.query("""
                SELECT CAST(placed_at AS DATE) AS bucket, source_type, COUNT(*) AS n FROM customer_order
                WHERE placed_at >= ? AND placed_at < ? GROUP BY CAST(placed_at AS DATE), source_type
                """, rs -> {
            long[] counts = placed.computeIfAbsent(rs.getDate("bucket").toLocalDate(), d -> new long[2]);
            counts["RFQ".equals(rs.getString("source_type")) ? 1 : 0] += rs.getLong("n");
        }, utc(from), utc(end));
        List<DailyPoint> daily = from.datesUntil(end).map(day -> {
            long[] counts = placed.getOrDefault(day, new long[2]);
            return new DailyPoint(day, collected.getOrDefault(day, BigDecimal.ZERO), counts[0], counts[1]);
        }).toList();

        return new Metrics(orders == null ? 0 : orders, collectedThisMonth, month.toString(), span, byStatus, daily);
    }

    /** Successful receipts per UTC day in [from, to). */
    private Map<LocalDate, BigDecimal> collectedByDay(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> totals = new HashMap<>();
        // Retail child payment rows are allocations of customer_payment, not extra collections.
        db.query("""
                SELECT CAST(paid_at AS DATE) AS bucket, SUM(amount) AS total FROM (
                    SELECT amount, paid_at FROM customer_payment WHERE status='SUCCESS' AND paid_at >= ? AND paid_at < ?
                    UNION ALL
                    SELECT p.amount, p.paid_at FROM payment p WHERE p.status='SUCCESS' AND p.paid_at >= ? AND p.paid_at < ?
                    AND NOT EXISTS (SELECT 1 FROM customer_payment_item i WHERE i.payment_id=p.payment_id)
                ) receipts GROUP BY CAST(paid_at AS DATE)
                """, rs -> {
            totals.put(rs.getDate("bucket").toLocalDate(), rs.getBigDecimal("total"));
        }, utc(from), utc(to), utc(from), utc(to));
        return totals;
    }

    /** Commerce stores UTC wall-clock times (see FinanceRepository.utc), so bind wall-clock values, not JVM-zone instants. */
    private static Timestamp utc(LocalDate day) { return Timestamp.valueOf(day.atStartOfDay()); }
}
