package mss.mooncake.rfqnegotiation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import mss.mooncake.rfqnegotiation.controller.AdminRfqMetricsController.KeyCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class AdminRfqMetricsControllerTest {
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private JdbcTemplate db;
    private AdminRfqMetricsController controller;

    @BeforeEach
    void setUp() {
        db = new JdbcTemplate(new SingleConnectionDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer", "sa", "", true));
        db.execute("CREATE TABLE rfq (status VARCHAR(30) NOT NULL, created_at DATETIME2 NOT NULL)");
        db.execute("CREATE TABLE quotation (status VARCHAR(30) NOT NULL)");
        controller = new AdminRfqMetricsController(db);

        rfq("OPEN", today);
        rfq("OPEN", today);
        rfq("CLOSED", today.minusDays(2));
        rfq("CANCELLED", today.minusDays(45));
        for (String status : new String[]{"SUBMITTED", "REVISED", "REVISED", "ACCEPTED", "SUBMITTED"}) {
            db.update("INSERT INTO quotation VALUES (?)", status);
        }
    }

    @Test
    void countsCurrentRfqAndLatestQuotationStatuses() {
        AdminRfqMetricsController.Metrics metrics = controller.metrics(30);

        assertThat(metrics.rfqs()).isEqualTo(4);
        assertThat(metrics.rfqsByStatus().getFirst()).isEqualTo(new KeyCount("OPEN", 2));
        assertThat(metrics.rfqsByStatus()).hasSize(3);
        assertThat(metrics.quotationsByStatus()).containsExactly(
                new KeyCount("SUBMITTED", 2), new KeyCount("ACCEPTED", 1));
    }

    @Test
    void countsNewRfqsPerUtcDayWithinTheRange() {
        AdminRfqMetricsController.Metrics metrics = controller.metrics(7);

        assertThat(metrics.days()).isEqualTo(7);
        assertThat(metrics.daily()).hasSize(7);
        assertThat(metrics.daily().getLast().date()).isEqualTo(today);
        assertThat(metrics.daily().getLast().count()).isEqualTo(2);
        assertThat(metrics.daily().get(4).count()).isEqualTo(1);
        assertThat(controller.metrics(500).daily()).hasSize(90);
    }

    private void rfq(String status, LocalDate createdOn) {
        db.update("INSERT INTO rfq VALUES (?, ?)", status, Timestamp.valueOf(createdOn.atTime(8, 30)));
    }
}
