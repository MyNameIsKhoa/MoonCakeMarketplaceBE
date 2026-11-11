package mss.mooncake.commerce.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class AdminCommerceMetricsControllerTest {
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private JdbcTemplate db;
    private AdminCommerceMetricsController controller;

    @BeforeEach
    void setUp() {
        db = new JdbcTemplate(new SingleConnectionDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer", "sa", "", true));
        db.execute("CREATE TABLE customer_order (source_type VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL, placed_at DATETIME2 NOT NULL)");
        db.execute("CREATE TABLE customer_payment (amount BIGINT NOT NULL, status VARCHAR(20) NOT NULL, paid_at DATETIME2 NULL)");
        db.execute("CREATE TABLE payment (payment_id BIGINT NOT NULL, amount DECIMAL(18,2) NOT NULL, status VARCHAR(30) NOT NULL, paid_at DATETIME2 NULL)");
        db.execute("CREATE TABLE customer_payment_item (payment_id BIGINT NOT NULL)");
        controller = new AdminCommerceMetricsController(db);

        order("RETAIL", "DELIVERED", today, 23);
        order("RFQ", "AWAITING_DEPOSIT", today, 0);
        order("RFQ", "AWAITING_DEPOSIT", today, 1);
        order("RETAIL", "CANCELLED", today.minusDays(3), 12);
        order("RETAIL", "SETTLED", today.minusDays(40), 12);

        db.update("INSERT INTO customer_payment VALUES (100000, 'SUCCESS', ?)", at(today, 23));
        db.update("INSERT INTO customer_payment VALUES (5, 'PENDING', ?)", at(today, 1));
        db.update("INSERT INTO payment VALUES (1, 300000, 'SUCCESS', ?)", at(today.minusDays(2), 9));
        // Allocated to a customer_payment: already counted through the parent receipt.
        db.update("INSERT INTO payment VALUES (2, 70000, 'SUCCESS', ?)", at(today, 9));
        db.update("INSERT INTO customer_payment_item VALUES (2)");
    }

    @Test
    void bucketsReceiptsAndNewOrdersByUtcDay() {
        var metrics = controller.metrics(7);

        assertThat(metrics.days()).isEqualTo(7);
        assertThat(metrics.daily()).hasSize(7);
        assertThat(metrics.daily().getFirst().date()).isEqualTo(today.minusDays(6));
        var last = metrics.daily().getLast();
        assertThat(last.date()).isEqualTo(today);
        assertThat(last.collected()).isEqualByComparingTo("100000");
        assertThat(last.retailOrders()).isEqualTo(1);
        assertThat(last.rfqOrders()).isEqualTo(2);
        var twoDaysAgo = metrics.daily().get(4);
        assertThat(twoDaysAgo.date()).isEqualTo(today.minusDays(2));
        assertThat(twoDaysAgo.collected()).isEqualByComparingTo("300000");
        assertThat(metrics.daily().get(3).retailOrders()).isEqualTo(1);
        assertThat(metrics.daily().get(0).collected()).isEqualByComparingTo("0");
    }

    @Test
    void keepsAllTimeTotalsAndCountsOrdersByStatus() {
        var metrics = controller.metrics(30);

        assertThat(metrics.orders()).isEqualTo(5);
        assertThat(metrics.month()).isEqualTo(YearMonth.from(today).toString());
        BigDecimal expectedMonth = YearMonth.from(today.minusDays(2)).equals(YearMonth.from(today))
                ? new BigDecimal("400000") : new BigDecimal("100000");
        assertThat(metrics.collectedThisMonth()).isEqualByComparingTo(expectedMonth);
        assertThat(metrics.ordersByStatus().getFirst())
                .isEqualTo(new AdminCommerceMetricsController.KeyCount("AWAITING_DEPOSIT", 2));
        assertThat(metrics.ordersByStatus()).extracting(AdminCommerceMetricsController.KeyCount::count)
                .containsOnly(2L, 1L).hasSize(4);
    }

    @Test
    void clampsTheRangeToBetweenOneAndNinetyDays() {
        assertThat(controller.metrics(0).daily()).hasSize(1);
        assertThat(controller.metrics(500).daily()).hasSize(90);
        assertThat(controller.metrics(500).days()).isEqualTo(90);
    }

    private void order(String source, String status, LocalDate day, int hour) {
        db.update("INSERT INTO customer_order VALUES (?, ?, ?)", source, status, at(day, hour));
    }

    private static Timestamp at(LocalDate day, int hour) {
        return Timestamp.valueOf(LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)));
    }
}
