package mss.mooncake.rfqnegotiation.controller;

import java.sql.Timestamp;
import java.time.LocalDate;
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
@RequestMapping("/api/admin/rfq")
public class AdminRfqMetricsController {
    public record KeyCount(String key, long count) { }
    public record DailyCount(LocalDate date, long count) { }
    /**
     * rfqs and the breakdowns are all-time; daily counts RFQs created over the last {@code days} UTC days.
     * quotationsByStatus skips REVISED rows: those are superseded versions, so each negotiation counts once.
     */
    public record Metrics(long rfqs, int days, List<KeyCount> rfqsByStatus,
                          List<KeyCount> quotationsByStatus, List<DailyCount> daily) { }

    private final JdbcTemplate db;

    public AdminRfqMetricsController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/metrics")
    public Metrics metrics(@RequestParam(defaultValue = "30") int days) {
        int span = Math.clamp(days, 1, 90);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(span - 1);
        LocalDate end = today.plusDays(1);

        Long rfqs = db.queryForObject("SELECT COUNT(*) FROM rfq", Long.class);
        List<KeyCount> rfqsByStatus = db.query("SELECT status, COUNT(*) AS n FROM rfq GROUP BY status ORDER BY n DESC",
                (rs, i) -> new KeyCount(rs.getString("status"), rs.getLong("n")));
        List<KeyCount> quotationsByStatus = db.query(
                "SELECT status, COUNT(*) AS n FROM quotation WHERE status <> 'REVISED' GROUP BY status ORDER BY n DESC",
                (rs, i) -> new KeyCount(rs.getString("status"), rs.getLong("n")));

        Map<LocalDate, Long> created = new HashMap<>();
        // created_at is a UTC wall-clock time (DEFAULT SYSUTCDATETIME()).
        db.query("""
                SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM rfq
                WHERE created_at >= ? AND created_at < ? GROUP BY CAST(created_at AS DATE)
                """, rs -> {
            created.put(rs.getDate("bucket").toLocalDate(), rs.getLong("n"));
        }, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(end.atStartOfDay()));
        List<DailyCount> daily = from.datesUntil(end)
                .map(day -> new DailyCount(day, created.getOrDefault(day, 0L))).toList();

        return new Metrics(rfqs == null ? 0 : rfqs, span, rfqsByStatus, quotationsByStatus, daily);
    }
}
