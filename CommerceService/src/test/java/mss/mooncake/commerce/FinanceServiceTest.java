package mss.mooncake.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.impl.FinanceServiceImpl;
import mss.mooncake.commerce.service.impl.FinanceIntegrationServiceImpl;
import mss.mooncake.commerce.service.CustomerPaymentService;
import mss.mooncake.commerce.service.impl.CustomerPaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.payos.model.webhooks.Webhook;

class FinanceServiceTest {
    private static final FinanceService.Actor BUYER = new FinanceService.Actor(101, 10L, null, false);
    private static final FinanceService.Actor ADMIN = new FinanceService.Actor(303, null, null, true);
    private FinanceRepository repository;
    private FinanceService service;
    private CustomerPaymentService customerPayments;
    private FinanceIntegrationService integration;
    private PaymentProvider provider;
    private TransactionTemplate transaction;
    private long sequence;
    private com.zaxxer.hikari.HikariDataSource datasource;

    @AfterEach
    void close() { if (datasource != null) { datasource.close(); } }

    @BeforeEach
    void setup() throws Exception {
        datasource = new com.zaxxer.hikari.HikariDataSource();
        datasource.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setMaximumPoolSize(5);
        var jdbc = new JdbcTemplate(datasource);
        // Execute the real migrations, adapting only SQL Server syntax unsupported by H2.
        for (String file : new String[]{"V1__commerce_physical_schema.sql", "V3__tv5_finance.sql",
                "V7__customer_order_payment.sql", "V8__seller_order_acceptance_timestamp.sql"}) {
            var resource = new ClassPathResource("db/migration/" + file);
            String sql = resource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("dbo.", "").replace("SYSUTCDATETIME()", "CURRENT_TIMESTAMP")
                    .replace("NVARCHAR(MAX)", "VARCHAR(2000)")
                    .replaceAll("CONSTRAINT DF_\\w+ DEFAULT", "DEFAULT")
                    .replace("CREATE UNIQUE INDEX", "CREATE UNIQUE NULLS DISTINCT INDEX")
                    .replaceAll("(?m)--[^\\r\\n]*", "")
                    .replaceAll("(?m)^GO\\s*$", "")
                    .replaceAll("(?i) WHERE [a-z_]+ IS NOT NULL;", ";");
            for (String statement : sql.split(";")) {
                String clean = statement.replaceAll("(?m)--[^\\r\\n]*", "").trim();
                if (clean.isEmpty()) { continue; }
                var matcher = java.util.regex.Pattern.compile("(?s)ALTER TABLE (\\w+) ADD (?!CONSTRAINT)(.*)").matcher(clean);
                if (matcher.matches()) {
                    for (String column : matcher.group(2).split(",")) {
                        jdbc.execute("ALTER TABLE " + matcher.group(1) + " ADD " + column.trim());
                    }
                } else { jdbc.execute(clean); }
            }
        }
        jdbc.execute("ALTER TABLE customer_order ADD stock_reservation_key VARCHAR(100) NULL");
        jdbc.update("""
                INSERT INTO customer_order(order_no,customer_id_ref,source_type,subtotal,grand_total,status)
                VALUES('TEST',10,'RETAIL',10001,10001,'ACCEPTED')
                """);
        jdbc.update("""
                INSERT INTO seller_order(order_id,seller_order_no,store_id_ref,supplier_id_ref,
                store_name_snapshot,supplier_name_snapshot,subtotal,total_amount,status)
                VALUES(1,'SELLER-TEST',1,20,'Store','Supplier',10001,10001,'ACCEPTED')
                """);
        repository = new mss.mooncake.commerce.repository.impl.FinanceRepositoryImpl(jdbc);
        var manager = new DataSourceTransactionManager(datasource);
        transaction = new TransactionTemplate(manager);
        integration = new FinanceIntegrationServiceImpl(repository);
        provider = mock(PaymentProvider.class);
        customerPayments = new CustomerPaymentServiceImpl(repository, provider, manager);
        service = new FinanceServiceImpl(repository, provider, manager, "TOTAL_COLLECTED", "HALF_UP", true, customerPayments, integration);
        when(provider.create(anyLong(), anyLong(), any(), anyString())).thenAnswer(call -> new PaymentProvider.Link(
                call.getArgument(0), call.getArgument(1), "link-" + call.getArgument(0), "https://pay.payos.vn/test", "qr"));
    }

    private String key() { return "test-key-" + (++sequence); }

    private void context(boolean accepted, Long deposit, long version, LocalDate delivery) {
        transaction.executeWithoutResult(status -> integration.syncOrder(
                new FinanceOrderContext(1, 10, 20, 10001, deposit, delivery, accepted, version)));
    }

    @Test
    void adminApprovesUnpaidRfqCancellationAndClosesOrder() {
        var jdbc = new JdbcTemplate(datasource);
        jdbc.update("UPDATE customer_order SET source_type='RFQ',rfq_id_ref=7,status='AWAITING_DEPOSIT' WHERE order_id=1");
        jdbc.update("UPDATE seller_order SET status='AWAITING_DEPOSIT' WHERE seller_order_id=1");
        context(true, 3000L, 1, LocalDate.now().plusDays(10));
        service.requestCancellation(1, "Buyer request", key(), BUYER);
        long cancellationId = repository.cancellations(1).getFirst().id();
        service.decideCancellation(1, cancellationId, true, "Approved unpaid RFQ", key(), ADMIN);
        assertThat(repository.cancellations(1).getFirst().status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM seller_order WHERE seller_order_id=1", String.class)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM customer_order WHERE order_id=1", String.class)).isEqualTo("CANCELLED");
    }

    private long fullPayment() {
        context(true, null, 1, LocalDate.now().plusDays(30));
        long id = repository.payments(1).getFirst().id();
        service.initiate(id, key(), BUYER);
        collect(id, "reference-" + id);
        return id;
    }

    private void collect(long id, String reference) {
        var p = repository.payment(id);
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(p.orderCode(), p.amount(), p.linkId(), reference, "VND"));
        service.webhook(new Webhook());
    }

    @Test
    void cancellingUnpaidRetailOrderCancelsPayOsLinkAndClosesOrder() {
        var jdbc = new JdbcTemplate(datasource);
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        var cancellation = new CustomerPaymentServiceImpl(repository, provider,
                new DataSourceTransactionManager(datasource), catalog, null, integration);
        jdbc.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        jdbc.update("UPDATE customer_order SET stock_reservation_key='checkout-stock' WHERE order_id=1");
        context(true, null, 1, null);
        var link = cancellation.initiate(1, key(), BUYER).payment();
        when(provider.cancel(link.orderCode())).thenReturn(new PaymentProvider.Status(
                link.orderCode(), link.amount(), 0, link.linkId(), "CANCELLED", null));
        when(catalog.cancelConfirmedStockReservation("checkout-stock"))
                .thenReturn(new mss.mooncake.commerce.client.dto.catalog.StockReservationResponse(
                        "checkout-stock", "CANCELLED", null, java.util.List.of()));

        cancellation.cancelUnpaidOrder(1, "Đổi ý trước khi thanh toán", BUYER);
        cancellation.cancelUnpaidOrder(1, "Đổi ý trước khi thanh toán", BUYER);

        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE order_id=1 AND status='CANCELLED'"))
                .isEqualTo(1);
        assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE seller_order_id=1 AND status='CANCELLED'"))
                .isEqualTo(1);
        assertThat(repository.cancellations(1).getFirst().status()).isEqualTo("APPROVED");
        assertThat(repository.paid(1)).isZero();
        verify(provider, times(1)).cancel(link.orderCode());
        verify(catalog, times(1)).cancelConfirmedStockReservation("checkout-stock");
    }

    @Test
    void legacyRetailOrderWithoutReservationKeyCannotBeCancelledWithoutRestoringStock() {
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        repository.update("UPDATE customer_order SET status='AWAITING_PAYMENT' WHERE order_id=1");
        context(true, null, 1, null);

        assertThatThrownBy(() -> customerPayments.cancelUnpaidOrder(1, "Đổi ý", BUYER))
                .hasMessage("STOCK_RESERVATION_RECONCILIATION_REQUIRED");

        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE order_id=1 AND status='AWAITING_PAYMENT'"))
                .isEqualTo(1);
        assertThat(repository.cancellations(1)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void expiredLocalLinkStillReconcilesBeforeCancellation() {
        var jdbc = new JdbcTemplate(datasource);
        jdbc.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        jdbc.update("UPDATE customer_order SET stock_reservation_key='checkout-stock' WHERE order_id=1");
        context(true, null, 1, null);
        var link = customerPayments.initiate(1, key(), BUYER).payment();
        jdbc.update("UPDATE customer_payment SET expires_at=? WHERE customer_payment_id=?",
                FinanceRepository.utc(Instant.now().minusSeconds(60)), link.id());
        when(provider.cancel(link.orderCode())).thenReturn(new PaymentProvider.Status(
                link.orderCode(), link.amount(), link.amount(), link.linkId(), "PAID", "late-paid"));

        assertThatThrownBy(() -> customerPayments.cancelUnpaidOrder(1, "Đổi ý trước khi thanh toán", BUYER))
                .hasMessage("PAYMENT_RECONCILIATION_REQUIRED");

        assertThat(repository.paid(1)).isEqualTo(10001);
        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE status='CANCELLED'")).isZero();
        verify(provider).cancel(link.orderCode());
    }

    @Test
    void fullCollectionUnlocksFulfillmentAndNeverRewindsShipping() {
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        long id = fullPayment();
        assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='READY_FOR_FULFILLMENT'"))
                .isEqualTo(1);
        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE status='READY_FOR_FULFILLMENT'"))
                .isEqualTo(1);
        repository.update("UPDATE seller_order SET status='SHIPPING' WHERE seller_order_id=1");
        collect(id, "reference-" + id);
        assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='SHIPPING'")).isEqualTo(1);
    }

    @Test
    void failedPayOsReconciliationNeverRestoresStockEvenAfterExpiry() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        var cancellation = new CustomerPaymentServiceImpl(repository, provider,
                new DataSourceTransactionManager(datasource), catalog, null, integration);
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        context(true, null, 1, null);
        var link = cancellation.initiate(1, key(), BUYER).payment();
        repository.update("UPDATE customer_order SET stock_reservation_key='checkout-stock' WHERE order_id=1");
        repository.update("UPDATE customer_payment SET expires_at=?", FinanceRepository.utc(Instant.now().minusSeconds(60)));
        when(provider.cancel(link.orderCode())).thenThrow(new IllegalStateException("provider unavailable"));
        assertThatThrownBy(() -> cancellation.cancelUnpaidOrder(1, "Test", BUYER))
                .hasMessage("PAYOS_RECONCILIATION_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(catalog);
        assertThat(repository.customerPayments(1).getFirst().status()).isEqualTo("PENDING");
        assertThat(repository.pendingCancellationOrderIds()).containsExactly(1L);
    }

    @Test
    void cancellationRecoversLostCatalogResponseWithTheSameReservationKey() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        var cancellation = new CustomerPaymentServiceImpl(repository, provider,
                new DataSourceTransactionManager(datasource), catalog, null, integration);
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        context(true, null, 1, null);
        repository.update("UPDATE customer_order SET stock_reservation_key='checkout-stock' WHERE order_id=1");
        when(catalog.cancelConfirmedStockReservation("checkout-stock"))
                .thenThrow(new IllegalStateException("response lost"))
                .thenReturn(new mss.mooncake.commerce.client.dto.catalog.StockReservationResponse(
                        "checkout-stock", "CANCELLED", null, java.util.List.of()));
        assertThatThrownBy(() -> cancellation.cancelUnpaidOrder(1, "Test", BUYER)).hasMessage("response lost");
        cancellation.recoverUnpaidCancellations();
        assertThat(repository.pendingCancellationOrderIds()).isEmpty();
        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE status='CANCELLED'")).isEqualTo(1);
        verify(catalog, times(2)).cancelConfirmedStockReservation("checkout-stock");
    }

    @Test
    void depositAllowsPreparationAndRemainingDoesNotUndoPreparation() {
        repository.update("UPDATE seller_order SET status='AWAITING_DEPOSIT' WHERE seller_order_id=1");
        context(true, 3000L, 1, LocalDate.now().plusDays(30));
        long id = repository.payments(1).getFirst().id();
        service.initiate(id, key(), BUYER);
        collect(id, "deposit-paid");
        assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='READY_FOR_FULFILLMENT'"))
                .isEqualTo(1);
        repository.update("UPDATE seller_order SET status='PREPARING' WHERE seller_order_id=1");
        long remaining = repository.payments(1).get(1).id();
        service.initiate(remaining, key(), BUYER);
        collect(remaining, "remaining-paid");
        assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='PREPARING'")).isEqualTo(1);
    }

    @Test
    void realJpaFulfillmentUsesPaymentFactsAndUpdatesSettlementFacts() {
        var factory = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(datasource);
        factory.setPackagesToScan("mss.mooncake.commerce.entity");
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try {
            var manager = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
            var tx = new TransactionTemplate(manager);
            var em = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
            var repos = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
            var fulfillment = new mss.mooncake.commerce.service.impl.FulfillmentApplicationServiceImpl(
                    repos.getRepository(mss.mooncake.commerce.repository.ShipmentRepository.class),
                    repos.getRepository(mss.mooncake.commerce.repository.ReviewRepository.class),
                    repos.getRepository(mss.mooncake.commerce.repository.SellerOrderRepository.class),
                    repos.getRepository(mss.mooncake.commerce.repository.CustomerOrderRepository.class),
                    repos.getRepository(mss.mooncake.commerce.repository.OrderItemRepository.class), repository, integration);
            repository.update("UPDATE seller_order SET status='AWAITING_DEPOSIT' WHERE seller_order_id=1");
            context(true, 3000L, 1, LocalDate.now().plusDays(30));
            long deposit = repository.payments(1).getFirst().id();
            service.initiate(deposit, key(), BUYER);
            collect(deposit, "jpa-deposit");
            var shipment = tx.execute(s -> fulfillment.createShipment(
                    new mss.mooncake.commerce.dto.request.CreateShipmentRequest(1L, "STANDARD", "TEST", "TRACK",
                            null, null, java.math.BigDecimal.ZERO), 20L));
            assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='PREPARING'")).isEqualTo(1);
            assertThatThrownBy(() -> tx.execute(s -> fulfillment.markAsShipped(shipment.shipmentId(), 20L)))
                    .hasMessageContaining("Insufficient payment");
            long remaining = repository.payments(1).get(1).id();
            service.initiate(remaining, key(), BUYER);
            collect(remaining, "jpa-remaining");
            tx.execute(s -> fulfillment.markAsShipped(shipment.shipmentId(), 20L));
            tx.execute(s -> fulfillment.markAsDelivered(shipment.shipmentId(), 20L, false));
            assertThat(repository.context(1).delivered()).isTrue();
            assertThat(repository.context(1).received()).isFalse();
            var accepted = tx.execute(s -> fulfillment.acceptSellerOrder(1L, 10L));
            var repeated = tx.execute(s -> fulfillment.acceptSellerOrder(1L, 10L));
            assertThat(repeated.acceptedAt()).isEqualTo(accepted.acceptedAt());
            assertThat(accepted.acceptedAt()).isNotNull();
            assertThat(repository.context(1).received()).isTrue();
            assertThat(repository.context(1).fulfillmentVersion()).isEqualTo(2);
            assertThat(service.summary(1, BUYER).eligibility().eligible()).isTrue();
            assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE status='ACCEPTED'")).isEqualTo(1);
            // JPA must read every state emitted by the JDBC finance writer.
            for (String state : java.util.List.of("CREATING", "RECONCILING", "CANCELLING", "CANCELLED", "EXPIRED")) {
                repository.update("UPDATE payment SET status=? WHERE payment_id=?", state, deposit);
                String persisted = tx.execute(s -> em.find(mss.mooncake.commerce.entity.PaymentEntity.class, deposit)
                        .getStatus().name());
                assertThat(persisted).isEqualTo(state);
            }
        } finally { factory.destroy(); }
    }

    @Test
    void oneCustomerCheckoutSplitsVerifiedPaymentAcrossSellersExactlyOnce() {
        var jdbc = new JdbcTemplate(datasource);
        jdbc.update("UPDATE customer_order SET grand_total=20002 WHERE order_id=1");
        jdbc.update("""
                INSERT INTO seller_order(order_id,seller_order_no,store_id_ref,supplier_id_ref,
                store_name_snapshot,supplier_name_snapshot,subtotal,total_amount,status)
                VALUES(1,'SELLER-SECOND',2,21,'Second store','Second supplier',10001,10001,'ACCEPTED')
                """);
        context(true, null, 1, null);
        transaction.executeWithoutResult(status -> integration.syncOrder(
                new FinanceOrderContext(2, 10, 21, 10001, null, null, true, 1)));

        String idempotencyKey = key();
        var first = customerPayments.initiate(1, idempotencyKey, BUYER);
        var repeated = customerPayments.initiate(1, idempotencyKey, BUYER);
        assertThat(first.payment().orderCode()).isEqualTo(repeated.payment().orderCode());
        assertThat(first.payment().orderCode()).isBetween(100_000_000_000_000L, 999_999_999_999_999L);
        assertThat(first.totalAmount()).isEqualTo(20002);
        assertThat(first.allocations()).hasSize(2);
        verify(provider, times(1)).create(anyLong(), anyLong(), any(), eq("DH TEST"));
        assertThatThrownBy(() -> service.initiate(repository.payments(1).getFirst().id(), key(), BUYER))
                .hasMessage("ACTIVE_PAYMENT_EXISTS");

        var group = first.payment();
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(group.orderCode(), 1,
                group.linkId(), "wrong-total", "VND"));
        assertThatThrownBy(() -> service.webhook(new Webhook())).hasMessage("WEBHOOK_PAYMENT_MISMATCH");
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(group.orderCode(), 20002,
                group.linkId(), "customer-payment-reference", "VND"));
        service.webhook(new Webhook());
        service.webhook(new Webhook());
        assertThat(repository.paid(1)).isEqualTo(10001);
        assertThat(repository.paid(2)).isEqualTo(10001);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger WHERE entry_type='HELD'")).isEqualTo(2);
        assertThat(repository.count("SELECT COUNT(*) FROM customer_payment_webhook_inbox")).isEqualTo(1);
        assertThat(customerPayments.summary(1, BUYER).payment().status()).isEqualTo("SUCCESS");
    }

    private void deliver() {
        transaction.executeWithoutResult(status -> integration.syncFulfillment(1, 1, true, true));
    }

    @Test
    void signedRegistrationProbeIsAcknowledgedWithoutCreatingMoney() {
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(123, 3000,
                "124c33293c43417ab7879e14c8d9eb18", "TF230204212323", "VND"));
        service.webhook(new Webhook());
        service.webhook(new Webhook());
        assertThat(repository.count("SELECT COUNT(*) FROM finance_provider_probe")).isEqualTo(1);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger")).isZero();
        assertThat(repository.count("SELECT COUNT(*) FROM payment")).isZero();
    }

    @Test
    void noObligationBeforeAcceptAndSnapshotEventsAreVersioned() {
        context(false, 3000L, 1, LocalDate.now().plusDays(30));
        assertThat(repository.payments(1)).isEmpty();
        context(true, 3000L, 2, LocalDate.now().plusDays(30));
        context(false, 3000L, 1, LocalDate.now().plusDays(30));
        assertThat(repository.context(1).accepted()).isTrue();
        assertThat(repository.payments(1)).hasSize(2);
        assertThatThrownBy(() -> context(false, 3000L, 2, LocalDate.now().plusDays(30)))
                .hasMessage("CONTEXT_VERSION_CONFLICT");
    }

    @Test
    void remainingUsesDeliveryMinusThreeDaysInVietnamAndCollectedBalance() {
        var delivery = LocalDate.of(2030, 10, 21);
        context(true, 3000L, 1, delivery);
        assertThat(FinanceIntegrationService.remainingDue(delivery)).isEqualTo(Instant.parse("2030-10-17T17:00:00Z"));
        long deposit = repository.payments(1).getFirst().id();
        long remaining = repository.payments(1).getLast().id();
        assertThatThrownBy(() -> service.initiate(remaining, key(), BUYER)).hasMessage("DEPOSIT_REQUIRED");
        service.initiate(deposit, key(), BUYER);
        collect(deposit, "deposit-ref");
        var link = service.initiate(remaining, key(), BUYER);
        assertThat(link.amount()).isEqualTo(7001);
        assertThat(link.dueAt()).isEqualTo(FinanceIntegrationService.remainingDue(delivery));
        verify(provider).create(anyLong(), anyLong(), any(), argThat(s -> s.matches("COC S20 [0-9]{10} [A-Z]{4}")));
        verify(provider).create(anyLong(), anyLong(), any(), argThat(s -> s.matches("CON S20 [0-9]{10} [A-Z]{4}")));
    }

    @Test
    void deadlineAlreadyPassedNeedsScheduleChange() {
        context(true, 3000L, 1, LocalDate.of(2020, 1, 1));
        var payments = repository.payments(1);
        service.initiate(payments.getFirst().id(), key(), BUYER);
        collect(payments.getFirst().id(), "deposit");
        assertThatThrownBy(() -> service.initiate(payments.getLast().id(), key(), BUYER)).hasMessage("PAYMENT_DEADLINE_PASSED");
        context(true, 3000L, 2, LocalDate.of(2030, 1, 1));
        assertThat(service.initiate(payments.getLast().id(), key(), BUYER).dueAt())
                .isEqualTo(FinanceIntegrationService.remainingDue(LocalDate.of(2030, 1, 1)));
    }

    @Test
    void rejectsMismatchedWebhookAndCreditsDuplicatesExactlyOnce() {
        context(true, null, 1, null);
        var id = repository.payments(1).getFirst().id();
        service.initiate(id, key(), BUYER);
        var p = repository.payment(id);
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(p.orderCode(), 1, p.linkId(), "bad", "VND"));
        assertThatThrownBy(() -> service.webhook(new Webhook())).hasMessage("WEBHOOK_PAYMENT_MISMATCH");
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(p.orderCode(), p.amount(), "wrong-link", "bad", "VND"));
        assertThatThrownBy(() -> service.webhook(new Webhook())).hasMessage("WEBHOOK_PAYMENT_MISMATCH");
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(999, p.amount(), p.linkId(), "bad", "VND"));
        assertThatThrownBy(() -> service.webhook(new Webhook())).hasMessage("UNKNOWN_ORDER_CODE");
        collect(id, "valid-ref");
        collect(id, "valid-ref");
        assertThat(repository.paid(1)).isEqualTo(10001);
        assertThat(repository.payment(id).hold()).isEqualTo("HELD");
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger")).isEqualTo(1);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_webhook_inbox")).isEqualTo(1);
    }

    @Test
    void timeoutRetryKeepsSameProviderOrderAndRequiresReconciliation() {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        when(provider.create(anyLong(), anyLong(), any(), anyString())).thenThrow(new RuntimeException("timeout"));
        String key = key();
        assertThat(service.initiate(id, key, BUYER).status()).isEqualTo("RECONCILING");
        assertThat(service.initiate(id, key, BUYER).status()).isEqualTo("RECONCILING");
        verify(provider, times(1)).create(anyLong(), anyLong(), any(), anyString());
        assertThatThrownBy(() -> service.initiate(id, key(), BUYER)).hasMessage("ACTIVE_PAYMENT_EXISTS");
        var p = repository.payment(id);
        when(provider.get(p.orderCode())).thenReturn(new PaymentProvider.Status(p.orderCode(), p.amount(), p.amount(), "link", "PAID", "reconciled-ref"));
        assertThat(service.reconcile(id, BUYER, false).status()).isEqualTo("SUCCESS");
        collect(id, "reconciled-ref");
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger")).isEqualTo(1);
    }

    /** PayOS makes the link, but its create response (with the checkout URL) never reaches us. */
    private void payOsLinkMadeButResponseLost() {
        doThrow(new RuntimeException("Read timed out")).when(provider).create(anyLong(), anyLong(), any(), anyString());
        doAnswer(call -> new PaymentProvider.Status(call.getArgument(0), 10001, 0, "lost-link", "PENDING", null))
                .when(provider).get(anyLong());
        doAnswer(call -> new PaymentProvider.Status(call.getArgument(0), 10001, 0, "lost-link", "CANCELLED", null))
                .when(provider).cancel(anyLong());
    }

    private void payOsCreatesLinks() {
        doAnswer(call -> new PaymentProvider.Link(call.getArgument(0), call.getArgument(1),
                "link-" + call.getArgument(0), "https://pay.payos.vn/test", "qr"))
                .when(provider).create(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void lostCreateResponseCancelsTheUnusableLinkAndARetryIssuesANewOne() {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        payOsLinkMadeButResponseLost();

        var lost = service.initiate(id, key(), BUYER);
        assertThat(lost.status()).isEqualTo("CANCELLED");
        verify(provider).cancel(repository.payment(id).orderCode());

        payOsCreatesLinks();
        var retry = service.initiate(id, key(), BUYER);
        assertThat(retry.status()).isEqualTo("PENDING");
        assertThat(retry.checkoutUrl()).isNotNull();
        assertThat(retry.paymentId()).isNotEqualTo(id);
    }

    @Test
    void laterReconciliationCancelsAPendingLinkWhoseUrlNeverReachedUs() {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        doThrow(new RuntimeException("Read timed out")).when(provider).create(anyLong(), anyLong(), any(), anyString());
        doThrow(new RuntimeException("connect timed out")).when(provider).get(anyLong());
        assertThat(service.initiate(id, key(), BUYER).status()).isEqualTo("RECONCILING");

        payOsLinkMadeButResponseLost();
        assertThat(service.reconcile(id, BUYER, false).status()).isEqualTo("CANCELLED");
    }

    @Test
    void reconciliationKeepsAPendingLinkWhoseUrlWeHave() {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        service.initiate(id, key(), BUYER);
        var p = repository.payment(id);
        doReturn(new PaymentProvider.Status(p.orderCode(), p.amount(), 0, p.linkId(), "PENDING", null))
                .when(provider).get(p.orderCode());

        assertThat(service.reconcile(id, BUYER, false).status()).isEqualTo("PENDING");
        verify(provider, never()).cancel(anyLong());
    }

    @Test
    void lateCreateResponseDoesNotReviveALinkCancelledMeanwhile() {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        payOsLinkMadeButResponseLost();
        doAnswer(call -> {
            // Another request reconciles while PayOS is still answering this create.
            service.reconcile(id, BUYER, false);
            return new PaymentProvider.Link(call.getArgument(0), call.getArgument(1), "lost-link",
                    "https://pay.payos.vn/late", "qr");
        }).when(provider).create(anyLong(), anyLong(), any(), anyString());

        assertThat(service.initiate(id, key(), BUYER).status()).isEqualTo("CANCELLED");
    }

    @Test
    void lostCustomerCreateResponseCancelsTheUnusableLinkAndARetryIssuesANewOne() {
        context(true, null, 1, null);
        payOsLinkMadeButResponseLost();

        assertThat(customerPayments.initiate(1, key(), BUYER).payment().status()).isEqualTo("CANCELLED");

        payOsCreatesLinks();
        var retry = customerPayments.initiate(1, key(), BUYER).payment();
        assertThat(retry.status()).isEqualTo("PENDING");
        assertThat(retry.checkoutUrl()).isNotNull();
    }

    @Test
    void manualRefundReservesAvailableMoneyAndNeedsProofBeforeLedgerDebit() {
        long paymentId = fullPayment();
        service.requestRefund(1, paymentId, 4000, "Approved cancellation", "policy-v1/manual decision #1", key(), ADMIN);
        assertThat(repository.refunds(1).getFirst().status()).isEqualTo("MANUAL_REVIEW");
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger")).isEqualTo(1);
        assertThatThrownBy(() -> service.requestRefund(1, paymentId, 7000, "reason", "policy-v1", key(), ADMIN))
                .hasMessage("REFUND_EXCEEDS_AVAILABLE_AMOUNT");
        long id = repository.refunds(1).getFirst().id();
        String key = key();
        service.confirmRefund(1, id, "bank-refund-1", "https://evidence.test/1", key, ADMIN);
        service.confirmRefund(1, id, "bank-refund-1", "https://evidence.test/1", key, ADMIN);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger WHERE entry_type='REFUNDED'")).isEqualTo(1);
    }

    @Test
    void snapshotsFeeAndReleasesOnlyAfterConfirmedPayoutExactlyOnce() {
        long paymentId = fullPayment();
        deliver();
        service.createSettlement(1, key(), ADMIN);
        var settlement = repository.settlements(1).getFirst();
        assertThat(settlement.feeAmount()).isEqualTo(500);
        assertThat(settlement.supplierPayoutAmount()).isEqualTo(9501);
        assertThat(settlement.roundingRule()).isEqualTo("HALF_UP");
        String key = key();
        service.beginPayout(1, settlement.settlementId(), key, ADMIN);
        service.beginPayout(1, settlement.settlementId(), key, ADMIN);
        assertThat(repository.payment(paymentId).hold()).isEqualTo("HELD");
        String confirmation = key();
        service.confirmPayout(1, settlement.settlementId(), "bank-settlement-1", "https://evidence.test/payout", confirmation, ADMIN);
        service.confirmPayout(1, settlement.settlementId(), "bank-settlement-1", "https://evidence.test/payout", confirmation, ADMIN);
        assertThat(repository.payment(paymentId).hold()).isEqualTo("RELEASED");
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger WHERE entry_type='RELEASED'")).isEqualTo(1);
        assertThatThrownBy(() -> service.requestRefund(1, paymentId, 1, "reason", "policy", key(), ADMIN))
                .hasMessage("PAYOUT_ALREADY_RESERVED");
    }

    @Test
    void openDisputePreventsSettlementAndPayoutEvenWhenSettlementAlreadyReady() {
        fullPayment(); deliver();
        service.createSettlement(1, key(), ADMIN);
        service.openDispute(1, "Damaged goods", "https://evidence.test/photo", key(), BUYER);
        long id = repository.settlements(1).getFirst().settlementId();
        assertThatThrownBy(() -> service.beginPayout(1, id, key(), ADMIN)).hasMessage("SETTLEMENT_NOT_ELIGIBLE");
        assertThat(service.summary(1, BUYER).eligibility().blockingReasons()).contains("OPEN_DISPUTE");
    }

    @Test
    void concurrentDisputeAndPayoutSerializeOnAggregate() throws Exception {
        fullPayment(); deliver(); service.createSettlement(1, key(), ADMIN);
        long id = repository.settlements(1).getFirst().settlementId();
        var latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var dispute = executor.submit(() -> {
                latch.await();
                try { service.openDispute(1, "reason", null, "concurrent-dispute", BUYER); return true; }
                catch (mss.mooncake.commerce.exception.FinanceException ex) { return false; }
            });
            var payout = executor.submit(() -> {
                latch.await();
                try { service.beginPayout(1, id, "concurrent-payout", ADMIN); return true; }
                catch (mss.mooncake.commerce.exception.FinanceException ex) { return false; }
            });
            latch.countDown();
            assertThat(dispute.get() ^ payout.get()).isTrue();
        }
    }

    @Test
    void ownershipAndIdempotencyAreEnforcedOnServer() {
        context(true, null, 1, null);
        new JdbcTemplate(datasource).update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        var stranger = new FinanceService.Actor(999, 99L, null, false);
        assertThatThrownBy(() -> service.summary(1, stranger)).hasMessage("FINANCE_ACCESS_DENIED");
        assertThatThrownBy(() -> service.initiate(repository.payments(1).getFirst().id(), key(), stranger))
                .hasMessage("FINANCE_ACCESS_DENIED");
        String key = key();
        service.requestCancellation(1, "reason A", key, BUYER);
        assertThatThrownBy(() -> service.requestCancellation(1, "reason B", key, BUYER)).hasMessage("IDEMPOTENCY_KEY_CONFLICT");
        assertThatThrownBy(() -> service.createSettlement(1, key(), BUYER)).hasMessage("FINANCE_ADMIN_REQUIRED");
    }

    @Test
    void supplierRoleDoesNotPreventBuyingOwnCustomerOrderAndOtherSupplierCannotInitiate() {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        service.initiate(id, key(), new FinanceService.Actor(101, 10L, 77L, false));
        assertThat(service.summary(1, new FinanceService.Actor(101, 10L, 77L, false)).payments().getFirst().checkoutUrl()).isNotNull();
        var seller = new FinanceService.Actor(202, 88L, 20L, false);
        assertThat(service.summary(1, seller).payments().getFirst().checkoutUrl()).isNull();
        var payment = repository.payment(id);
        when(provider.get(payment.orderCode())).thenReturn(new PaymentProvider.Status(payment.orderCode(),
                payment.amount(), 0, payment.linkId(), "PENDING", null));
        assertThat(service.reconcile(id, seller, false).checkoutUrl()).isNull();
        collect(id, "supplier-view-ref");
        assertThat(service.reconcile(id, seller, false).checkoutUrl()).isNull();
        assertThatThrownBy(() -> service.initiate(id, key(), seller)).hasMessage("FINANCE_ACCESS_DENIED");
    }

    @Test
    void concurrentDuplicateWebhooksOnlyCreditOneLedgerEntry() throws Exception {
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();
        service.initiate(id, key(), BUYER);
        var p = repository.payment(id);
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(p.orderCode(), p.amount(), p.linkId(), "same-reference", "VND"));
        var latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { latch.await(); service.webhook(new Webhook()); return true; });
            var second = executor.submit(() -> { latch.await(); service.webhook(new Webhook()); return true; });
            latch.countDown();
            assertThat(first.get() && second.get()).isTrue();
        }
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger")).isEqualTo(1);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_outbox WHERE event_type='PAYMENT_SUCCESS'")).isEqualTo(1);
    }

    @Test
    void userAndProfileIdCollisionsCannotGrantFinanceAccess() {
        fullPayment();
        var customerCollision = new FinanceService.Actor(10, 99L, null, false);
        var supplierCollision = new FinanceService.Actor(20, null, 99L, false);
        var noProfiles = new FinanceService.Actor(10, null, null, false);
        for (var actor : new FinanceService.Actor[]{customerCollision, supplierCollision, noProfiles}) {
            assertThatThrownBy(() -> service.summary(1, actor)).hasMessage("FINANCE_ACCESS_DENIED");
            assertThatThrownBy(() -> service.initiate(repository.payments(1).getFirst().id(), key(), actor))
                    .hasMessage("FINANCE_ACCESS_DENIED");
            assertThatThrownBy(() -> service.openDispute(1, "reason", null, key(), actor))
                    .hasMessage("FINANCE_ACCESS_DENIED");
        }
    }

    @Test
    void auditAndDisputeRecordUserIdRatherThanCustomerProfileId() {
        fullPayment();
        new JdbcTemplate(datasource).update("UPDATE seller_order SET status='PREPARING' WHERE seller_order_id=1");
        service.openDispute(1, "reason", null, key(), BUYER);
        assertThat(repository.count("SELECT opened_by_user_id_ref FROM dispute")).isEqualTo(101);
        assertThat(repository.history(1).getFirst().actor()).isEqualTo("101");
        service.requestCancellation(1, "reason", key(), BUYER);
        assertThat(repository.count("SELECT actor_id_ref FROM cancellation")).isEqualTo(101);
    }

    @Test
    void policiesAndSuccessfulTransferProofAreRequired() {
        long id = fullPayment();
        deliver();
        var unconfigured = new FinanceServiceImpl(repository, provider, new DataSourceTransactionManager(datasource), "", "", false);
        assertThat(unconfigured.summary(1, BUYER).eligibility().blockingReasons())
                .contains("FEE_BASIS_NOT_AGREED", "ROUNDING_NOT_AGREED");
        assertThatThrownBy(() -> unconfigured.createSettlement(1, key(), ADMIN)).hasMessage("SETTLEMENT_NOT_ELIGIBLE");
        assertThatThrownBy(() -> service.requestRefund(1, id, 100, "reason", "", key(), ADMIN))
                .hasMessage("REFUND_POLICY_DECISION_REQUIRED");
        service.requestRefund(1, id, 100, "reason", "policy-v1/manual", key(), ADMIN);
        long refund = repository.refunds(1).getFirst().id();
        assertThatThrownBy(() -> service.confirmRefund(1, refund, "", "", key(), ADMIN))
                .hasMessage("TRANSFER_PROOF_REQUIRED");
        assertThat(repository.refunds(1).getFirst().status()).isEqualTo("MANUAL_REVIEW");
    }
}
