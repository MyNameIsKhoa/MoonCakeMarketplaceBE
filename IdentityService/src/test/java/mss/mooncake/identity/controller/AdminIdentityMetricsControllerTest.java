package mss.mooncake.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import mss.mooncake.identity.controller.AdminIdentityMetricsController.KeyCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class AdminIdentityMetricsControllerTest {
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);
    private JdbcTemplate db;
    private AdminIdentityMetricsController controller;

    @BeforeEach
    void setUp() {
        db = new JdbcTemplate(new SingleConnectionDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer", "sa", "", true));
        db.execute("CREATE TABLE app_user (user_id BIGINT NOT NULL PRIMARY KEY, created_at DATETIME2 NOT NULL)");
        db.execute("CREATE TABLE customer_profile (user_id BIGINT NOT NULL UNIQUE)");
        db.execute("CREATE TABLE supplier_profile (user_id BIGINT NOT NULL UNIQUE, verification_status VARCHAR(30) NOT NULL)");
        controller = new AdminIdentityMetricsController(db);

        user(1, today, true, null);           // customer
        user(2, today, true, "VERIFIED");     // supplier that also buys
        user(3, today.minusDays(1), false, "PENDING");
        user(4, today.minusDays(1), false, "PENDING");
        user(5, today.minusDays(60), false, null); // admin / no profile
    }

    @Test
    void classifiesEveryUserIntoExactlyOneRole() {
        var metrics = controller.metrics(30);

        assertThat(metrics.users()).isEqualTo(5);
        assertThat(metrics.usersByRole()).containsExactlyInAnyOrder(
                new KeyCount("SUPPLIER", 3), new KeyCount("CUSTOMER", 1), new KeyCount("OTHER", 1));
        assertThat(metrics.suppliersByVerification()).containsExactly(
                new KeyCount("PENDING", 2), new KeyCount("VERIFIED", 1));
    }

    @Test
    void countsSignUpsPerUtcDayWithinTheRange() {
        var metrics = controller.metrics(7);

        assertThat(metrics.days()).isEqualTo(7);
        assertThat(metrics.daily()).hasSize(7);
        assertThat(metrics.daily().getLast().date()).isEqualTo(today);
        assertThat(metrics.daily().getLast().count()).isEqualTo(2);
        assertThat(metrics.daily().get(5).count()).isEqualTo(2);
        assertThat(metrics.daily().stream().mapToLong(AdminIdentityMetricsController.DailyCount::count).sum())
                .isEqualTo(4);
    }

    private void user(long id, LocalDate createdOn, boolean customer, String supplierStatus) {
        db.update("INSERT INTO app_user VALUES (?, ?)", id, Timestamp.valueOf(createdOn.atTime(12, 0)));
        if (customer) db.update("INSERT INTO customer_profile VALUES (?)", id);
        if (supplierStatus != null) db.update("INSERT INTO supplier_profile VALUES (?, ?)", id, supplierStatus);
    }
}
