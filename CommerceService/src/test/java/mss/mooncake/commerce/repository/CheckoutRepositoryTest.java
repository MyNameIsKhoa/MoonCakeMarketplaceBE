package mss.mooncake.commerce.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.time.LocalDateTime;
import mss.mooncake.commerce.repository.impl.CheckoutRepositoryImpl;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.service.OrderNumberFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

class CheckoutRepositoryTest {
    @Test
    void completingSelectedLinesKeepsRemainingCartAndCanBeRetried() {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        try {
            var db = new JdbcTemplate(database);
            db.execute("CREATE ALIAS SYSUTCDATETIME FOR 'java.time.LocalDateTime.now()'");
            db.execute("CREATE TABLE cart(cart_id BIGINT PRIMARY KEY, status VARCHAR(30), updated_at TIMESTAMP)");
            db.execute("CREATE TABLE cart_item(cart_item_id BIGINT PRIMARY KEY, cart_id BIGINT, variant_id_ref BIGINT, quantity INT)");
            db.execute("CREATE TABLE checkout_attempt(attempt_id BIGINT PRIMARY KEY, status VARCHAR(30), updated_at TIMESTAMP)");
            db.execute("CREATE TABLE customer_order(order_id BIGINT PRIMARY KEY, status VARCHAR(30), stock_reservation_key VARCHAR(100))");
            db.execute("CREATE TABLE seller_order(seller_order_id BIGINT PRIMARY KEY, order_id BIGINT, supplier_id_ref BIGINT, total_amount DECIMAL(18,2), status VARCHAR(30))");
            db.execute("CREATE TABLE order_item(seller_order_id BIGINT, variant_id_ref BIGINT, quantity INT)");
            db.update("INSERT INTO cart VALUES (9, 'CHECKOUT_PENDING', CURRENT_TIMESTAMP)");
            db.update("INSERT INTO cart_item VALUES (3, 9, 41, 2), (4, 9, 42, 5), (5, 9, 43, 4)");
            db.update("INSERT INTO checkout_attempt VALUES (1, 'PENDING_STOCK', CURRENT_TIMESTAMP)");
            db.update("INSERT INTO customer_order VALUES (17, 'PENDING_STOCK', NULL)");
            db.update("INSERT INTO seller_order VALUES (31, 17, 6, 100, 'PENDING_STOCK')");
            db.update("INSERT INTO order_item VALUES (31, 41, 2), (31, 42, 1)");

            var finance = mock(FinanceIntegrationService.class);
            var repository = new CheckoutRepositoryImpl(db, mock(OrderNumberFormatter.class), finance);
            var attempt = new CheckoutRepository.Attempt(1, 7, 9, 11, "checkout-key", "fingerprint",
                    "reservation", LocalDateTime.now().plusMinutes(15), "PENDING_STOCK", 17L, "3,4:null:quote");
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
            transactions.executeWithoutResult(status -> repository.complete(attempt));
            transactions.executeWithoutResult(status -> repository.complete(attempt));

            assertThat(db.queryForList("SELECT cart_item_id FROM cart_item ORDER BY cart_item_id", Long.class))
                    .containsExactly(4L, 5L);
            assertThat(db.queryForList("SELECT quantity FROM cart_item ORDER BY cart_item_id", Integer.class))
                    .containsExactly(4, 4);
            assertThat(db.queryForObject("SELECT status FROM cart WHERE cart_id=9", String.class)).isEqualTo("ACTIVE");
            assertThat(db.queryForObject("SELECT status FROM customer_order WHERE order_id=17", String.class))
                    .isEqualTo("AWAITING_PAYMENT");
            verify(finance, times(1)).syncOrder(any());
        } finally {
            database.shutdown();
        }
    }
}
