package mss.mooncake.identity.controller;

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
@RequestMapping("/api/admin/identity")
public class AdminIdentityMetricsController {
    public record KeyCount(String key, long count) { }
    public record DailyCount(LocalDate date, long count) { }
    /**
     * users and the breakdowns are all-time; daily counts sign-ups over the last {@code days} UTC days.
     * usersByRole: SUPPLIER = has a supplier profile, CUSTOMER = customer profile only, OTHER = neither.
     */
    public record Metrics(long users, int days, List<KeyCount> usersByRole,
                          List<KeyCount> suppliersByVerification, List<DailyCount> daily) { }

    private final JdbcTemplate db;

    public AdminIdentityMetricsController(JdbcTemplate db) { this.db = db; }

    @GetMapping("/metrics")
    public Metrics metrics(@RequestParam(defaultValue = "30") int days) {
        int span = Math.clamp(days, 1, 90);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(span - 1);
        LocalDate end = today.plusDays(1);

        Long users = db.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        List<KeyCount> byRole = db.query("""
                SELECT role, COUNT(*) AS n FROM (
                    SELECT CASE WHEN s.user_id IS NOT NULL THEN 'SUPPLIER'
                                WHEN c.user_id IS NOT NULL THEN 'CUSTOMER' ELSE 'OTHER' END AS role
                    FROM app_user u
                    LEFT JOIN supplier_profile s ON s.user_id = u.user_id
                    LEFT JOIN customer_profile c ON c.user_id = u.user_id
                ) roles GROUP BY role ORDER BY n DESC
                """, (rs, i) -> new KeyCount(rs.getString("role"), rs.getLong("n")));
        List<KeyCount> byVerification = db.query(
                "SELECT verification_status, COUNT(*) AS n FROM supplier_profile GROUP BY verification_status ORDER BY n DESC",
                (rs, i) -> new KeyCount(rs.getString("verification_status"), rs.getLong("n")));

        Map<LocalDate, Long> signUps = new HashMap<>();
        // created_at defaults to SYSUTCDATETIME(), so it is a UTC wall-clock time.
        db.query("""
                SELECT CAST(created_at AS DATE) AS bucket, COUNT(*) AS n FROM app_user
                WHERE created_at >= ? AND created_at < ? GROUP BY CAST(created_at AS DATE)
                """, rs -> {
            signUps.put(rs.getDate("bucket").toLocalDate(), rs.getLong("n"));
        }, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(end.atStartOfDay()));
        List<DailyCount> daily = from.datesUntil(end)
                .map(day -> new DailyCount(day, signUps.getOrDefault(day, 0L))).toList();

        return new Metrics(users == null ? 0 : users, span, byRole, byVerification, daily);
    }
}
