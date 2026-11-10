package mss.mooncake.identity.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Uses an isolated database: never migrates, cleans or repairs an application database. */
@EnabledIfEnvironmentVariable(named = "TEST_SQLSERVER_URL", matches = "jdbc:sqlserver:.*")
class IdentitySqlServerMigrationTest {
    @Test
    void upgradesPopulatedV7WithDefaultAndNonnegativeAttemptCount() throws Exception {
        String serverUrl = System.getenv("TEST_SQLSERVER_URL");
        String user = System.getenv("TEST_SQLSERVER_USERNAME");
        String password = System.getenv("TEST_SQLSERVER_PASSWORD");
        String database = "mss_identity_migration_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUrl = serverUrl.replaceAll("(?i);databaseName=[^;]*", "") + ";databaseName=" + database;

        try (var admin = DriverManager.getConnection(serverUrl, user, password)) {
            admin.setCatalog("master");
            try (var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE [" + database + "]");
            }
            try {
                var dataSource = new DriverManagerDataSource(databaseUrl, user, password);
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("7").load().migrate();
                var jdbc = new JdbcTemplate(dataSource);
                jdbc.update("INSERT INTO dbo.app_user(email,status) VALUES ('migration-test@example.invalid','ACTIVE')");
                jdbc.update("""
                        INSERT INTO dbo.auth_action_token(user_id,token_hash,purpose,expires_at)
                        SELECT user_id,REPLICATE('a',64),'PASSWORD_RESET',DATEADD(MINUTE,10,SYSUTCDATETIME())
                        FROM dbo.app_user WHERE email='migration-test@example.invalid'
                        """);

                var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT failed_attempts FROM dbo.auth_action_token", Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.app_user", Integer.class)).isEqualTo(1);
                assertThatThrownBy(() -> jdbc.update("UPDATE dbo.auth_action_token SET failed_attempts=-1"))
                        .isInstanceOf(DataIntegrityViolationException.class);
                jdbc.update("UPDATE dbo.auth_action_token SET failed_attempts=2");
                assertThat(jdbc.queryForObject("SELECT failed_attempts FROM dbo.auth_action_token", Integer.class)).isEqualTo(2);
                flyway.validate();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
            } finally {
                // This exact random name was created above; an application database cannot match it.
                if (!database.matches("mss_identity_migration_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test database");
                try (var statement = admin.createStatement()) {
                    statement.execute("ALTER DATABASE [" + database + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
                    statement.execute("DROP DATABASE [" + database + "]");
                }
            }
        }
    }
}
