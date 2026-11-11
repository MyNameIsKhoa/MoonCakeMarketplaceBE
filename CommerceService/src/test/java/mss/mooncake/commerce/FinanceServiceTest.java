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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.commerce.client.dto.identity.PayoutAccountResponse;
import mss.mooncake.commerce.notification.CommerceNotifier;
import mss.mooncake.commerce.client.dto.catalog.StockReservationRequest;
import mss.mooncake.commerce.client.dto.catalog.StockRestoreRequest;
import mss.mooncake.commerce.client.dto.catalog.StockRestoreResponse;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.dto.response.FinanceResponse;
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
import org.mockito.ArgumentCaptor;
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
                "V7__customer_order_payment.sql", "V8__seller_order_acceptance_timestamp.sql",
                "V10__dispute_workflow.sql", "V11__cancellation_retry_and_stock_restore.sql",
                "V12__shipment_line.sql", "V13__seller_order_stock_restore.sql",
                "V14__manual_transfer_destination.sql", "V16__cancellation_retained_deposit.sql"}) {
            var resource = new ClassPathResource("db/migration/" + file);
            String sql = resource.getContentAsString(StandardCharsets.UTF_8)
                    .replace("dbo.", "").replace("SYSUTCDATETIME()", "CURRENT_TIMESTAMP")
                    .replace("NVARCHAR(MAX)", "VARCHAR(2000)")
                    .replaceAll("CONSTRAINT DF_\\w+ DEFAULT", "DEFAULT")
                    .replace("CREATE UNIQUE INDEX", "CREATE UNIQUE NULLS DISTINCT INDEX")
                    .replaceAll("(?m)--[^\\r\\n]*", "")
                    .replaceAll("(?m)^GO\\s*$", "")
                    .replaceAll("(?i) WHERE [a-z_]+ IS NOT NULL;", ";")
                    // H2 has no filtered index; the service enforces one open case per seller order.
                    .replaceAll("(?i)CREATE UNIQUE NULLS DISTINCT INDEX \\w+ ON \\w+\\(\\w+\\)\\s+WHERE \\w+ IN \\([^)]*\\);", ";")
                    // H2 keeps a dropped unique constraint's index while a foreign key reuses it.
                    .replace("ALTER TABLE cancellation DROP CONSTRAINT UQ_cancellation_seller_order;", """
                            ALTER TABLE cancellation DROP CONSTRAINT FK_cancellation_seller_order;
                            ALTER TABLE cancellation DROP CONSTRAINT UQ_cancellation_seller_order;
                            ALTER TABLE cancellation ADD CONSTRAINT FK_cancellation_seller_order
                            FOREIGN KEY (seller_order_id) REFERENCES seller_order(seller_order_id);""");
            for (String statement : sql.split(";")) {
                String clean = statement.replaceAll("(?m)--[^\\r\\n]*", "").trim();
                if (clean.isEmpty()) { continue; }
                var matcher = java.util.regex.Pattern.compile("(?s)ALTER TABLE (\\w+) ADD (?!CONSTRAINT)(.*)").matcher(clean);
                if (matcher.matches()) {
                    for (String column : matcher.group(2).split(",\\s*(?=[a-z_]+\\s)")) {
                        jdbc.execute("ALTER TABLE " + matcher.group(1) + " ADD " + column.trim());
                    }
                } else { jdbc.execute(clean); }
            }
        }
        jdbc.execute("ALTER TABLE customer_order ADD stock_reservation_key VARCHAR(100) NULL");
        // Seller-order payment links are RFQ-only; retail tests switch the order with retail().
        jdbc.update("""
                INSERT INTO customer_order(order_no,customer_id_ref,source_type,rfq_id_ref,subtotal,grand_total,status)
                VALUES('TEST',10,'RFQ',1,10001,10001,'ACCEPTED')
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
        identity = mock(IdentityServiceClient.class);
        stubAccounts();
        customerPayments = new CustomerPaymentServiceImpl(repository, provider, manager);
        service = new FinanceServiceImpl(repository, provider, manager, "TOTAL_COLLECTED", "HALF_UP", true,
                customerPayments, integration, 7, CommerceNotifier.disabled(), identity);
        when(provider.create(anyLong(), anyLong(), any(), anyString())).thenAnswer(call -> new PaymentProvider.Link(
                call.getArgument(0), call.getArgument(1), "link-" + call.getArgument(0), "https://pay.payos.vn/test", "qr"));
    }

    private String key() { return "test-key-" + (++sequence); }

    private void stubAccounts() {
        when(identity.getCustomerPayoutAccount(10L)).thenReturn(
                new PayoutAccountResponse("CUSTOMER", "970436", "Vietcombank", "123456789", "Buyer"));
        when(identity.getSupplierPayoutAccount(20L)).thenReturn(
                new PayoutAccountResponse("SUPPLIER", "970422", "MBBank", "987654321", "Supplier"));
    }

    private void prepareRefund(long orderId, long refundId) {
        service.prepareRefundTransfer(orderId, refundId, key(), ADMIN);
    }

    /** Retail checkout: paid only through the one customer-order payment. */
    private void retail() {
        repository.update("UPDATE customer_order SET source_type='RETAIL',rfq_id_ref=NULL WHERE order_id=1");
    }

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
        retail();
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
        retail();
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
        retail();
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
        retail();
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
        retail();
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

    /** Cancellation with seller payment links reconciled through the real finance service. */
    private CustomerPaymentServiceImpl rfqCancellation(mss.mooncake.commerce.client.CatalogServiceClient catalog) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<FinanceService> finance =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(finance.getObject()).thenAnswer(call -> service);
        return new CustomerPaymentServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                catalog, finance, integration);
    }

    /** The seeded order as quotation conversion persists it: RFQ source, no stock reservation. */
    private void rfqOrder(Long deposit, Instant placedAt) {
        String status = deposit == null ? "AWAITING_PAYMENT" : "AWAITING_DEPOSIT";
        repository.update("""
                UPDATE customer_order SET source_type='RFQ',rfq_id_ref=77,status=?,placed_at=? WHERE order_id=1
                """, status, FinanceRepository.utc(placedAt));
        repository.update("UPDATE seller_order SET status=? WHERE seller_order_id=1", status);
        context(true, deposit, 1, LocalDate.now().plusDays(30));
    }

    private long openDepositLink(String remoteStatus, long remotePaid) {
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        var link = repository.payment(deposit);
        when(provider.cancel(link.orderCode())).thenReturn(new PaymentProvider.Status(link.orderCode(),
                link.amount(), remotePaid, link.linkId(), remoteStatus, remotePaid > 0 ? "deadline-paid" : null));
        return deposit;
    }

    private long orders(String status) {
        return repository.count("SELECT COUNT(*) FROM customer_order WHERE order_id=1 AND status=?", status)
                + repository.count("SELECT COUNT(*) FROM seller_order WHERE seller_order_id=1 AND status=?", status);
    }

    @Test
    void buyerCancelsUnpaidRfqOrderAndItsOpenDepositLinkWithoutTouchingStock() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        var cancellation = rfqCancellation(catalog);
        rfqOrder(3000L, Instant.now());
        long deposit = openDepositLink("CANCELLED", 0);

        cancellation.cancelUnpaidOrder(1, "Đổi ý trước khi đặt cọc", BUYER);
        cancellation.cancelUnpaidOrder(1, "Đổi ý trước khi đặt cọc", BUYER);

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM cancellation WHERE seller_order_id=1 AND status='APPROVED'
                AND actor_type='CUSTOMER' AND actor_id_ref=101
                """)).isEqualTo(1);
        assertThat(repository.payment(deposit).status()).isEqualTo("CANCELLED");
        verify(provider, times(1)).cancel(repository.payment(deposit).orderCode());
        org.mockito.Mockito.verifyNoInteractions(catalog);
        for (var obligation : repository.payments(1)) {
            assertThatThrownBy(() -> service.initiate(obligation.id(), key(), BUYER))
                    .hasMessage("PAYMENT_BLOCKED_BY_CASE");
        }
    }

    @Test
    void buyerCancelsUnpaidRfqOrderWithoutDepositThatAwaitsFullPayment() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(null, Instant.now());

        cancellation.cancelUnpaidOrder(1, "Không còn nhu cầu", BUYER);

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.cancellations(1).getFirst().status()).isEqualTo("APPROVED");
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void rfqOrderWithPaidDepositCannotBeCancelledAsUnpaid() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        collect(deposit, "deposit-paid");

        assertThatThrownBy(() -> cancellation.cancelUnpaidOrder(1, "Đổi ý", BUYER))
                .isInstanceOf(mss.mooncake.commerce.exception.FinanceException.class)
                .hasMessage("ORDER_ALREADY_PAID");

        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
    }

    @Test
    void rfqOrderStillUnpaid48HoursAfterPlacementIsCancelledBySystem() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        var cancellation = rfqCancellation(catalog);
        rfqOrder(3000L, Instant.now().minus(java.time.Duration.ofHours(49)));
        long deposit = openDepositLink("CANCELLED", 0);

        cancellation.cancelOverdueRfqOrders();
        cancellation.cancelOverdueRfqOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM cancellation WHERE seller_order_id=1 AND status='APPROVED'
                AND actor_type='SYSTEM' AND actor_id_ref=0 AND reason=? AND decision_reason=?
                """, "Quá hạn đặt cọc 48 giờ", "Quá hạn đặt cọc 48 giờ")).isEqualTo(1);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM finance_audit WHERE seller_order_id=1
                AND event_type='CANCELLATION_DECIDED' AND actor='SYSTEM'
                """)).isEqualTo(1);
        assertThat(repository.payment(deposit).status()).isEqualTo("CANCELLED");
        verify(provider, times(1)).cancel(repository.payment(deposit).orderCode());
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void rfqOrderYoungerThan48HoursIsNotAutoCancelled() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(3000L, Instant.now().minus(java.time.Duration.ofHours(47)));

        cancellation.cancelOverdueRfqOrders();

        assertThat(orders("AWAITING_DEPOSIT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void overdueRetailOrderIsNeverAutoCancelled() {
        retail();
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        var cancellation = rfqCancellation(catalog);
        repository.update("UPDATE customer_order SET status='AWAITING_PAYMENT',placed_at=? WHERE order_id=1",
                FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(5))));
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        context(true, null, 1, null);

        cancellation.cancelOverdueRfqOrders();

        assertThat(orders("AWAITING_PAYMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(catalog, provider);
    }

    @Test
    void overdueRfqOrderWhoseLinkCannotBeReconciledIsSkippedAndRetriedNextRun() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(3000L, Instant.now().minus(java.time.Duration.ofHours(49)));
        long deposit = openDepositLink("CANCELLED", 0);
        var link = repository.payment(deposit);
        when(provider.cancel(link.orderCode())).thenThrow(new IllegalStateException("provider unavailable"))
                .thenReturn(new PaymentProvider.Status(link.orderCode(), link.amount(), 0, link.linkId(),
                        "CANCELLED", null));

        cancellation.cancelOverdueRfqOrders();

        assertThat(orders("AWAITING_DEPOSIT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
        assertThat(repository.payment(deposit).status()).isEqualTo("PENDING");

        cancellation.cancelOverdueRfqOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
    }

    @Test
    void overdueRfqDepositPaidAtTheDeadlineIsCollectedInsteadOfCancelled() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(3000L, Instant.now().minus(java.time.Duration.ofHours(49)));
        openDepositLink("PAID", 3000);

        cancellation.cancelOverdueRfqOrders();

        assertThat(repository.paid(1)).isEqualTo(3000);
        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
    }

    @Test
    void concurrentAutoCancellationRunsCancelTheOrderExactlyOnce() throws Exception {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(null, Instant.now().minus(java.time.Duration.ofHours(49)));
        var latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { latch.await(); cancellation.cancelOverdueRfqOrders(); return true; });
            var second = executor.submit(() -> { latch.await(); cancellation.cancelOverdueRfqOrders(); return true; });
            latch.countDown();
            assertThat(first.get() && second.get()).isTrue();
        }
        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.count("SELECT COUNT(*) FROM cancellation WHERE status='APPROVED'")).isEqualTo(1);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM finance_audit WHERE event_type='CANCELLATION_DECIDED'
                """)).isEqualTo(1);
    }

    // --- Paid cancellations: approved once confirmed refunds cover the collection (QA B1) ---

    private mss.mooncake.commerce.client.CatalogServiceClient restoringCatalog() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        when(catalog.cancelConfirmedStockReservation("checkout-stock")).thenReturn(cancelledStock());
        return catalog;
    }

    private static mss.mooncake.commerce.client.dto.catalog.StockReservationResponse cancelledStock() {
        return new mss.mooncake.commerce.client.dto.catalog.StockReservationResponse(
                "checkout-stock", "CANCELLED", null, java.util.List.of());
    }

    private CustomerPaymentServiceImpl retailPayments(mss.mooncake.commerce.client.CatalogServiceClient catalog) {
        return new CustomerPaymentServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                catalog, null, integration);
    }

    private FinanceService financeWith(CustomerPaymentServiceImpl payments) {
        return new FinanceServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                "TOTAL_COLLECTED", "HALF_UP", true, payments, integration, 7, CommerceNotifier.disabled(), identity);
    }

    /** A retail checkout paid in full through its one customer-order payment link. */
    private void paidRetailCheckout(CustomerPaymentServiceImpl payments, FinanceService finance, boolean twoSellers) {
        repository.update("""
                UPDATE customer_order SET source_type='RETAIL',rfq_id_ref=NULL,status='AWAITING_PAYMENT',
                stock_reservation_key='checkout-stock',grand_total=? WHERE order_id=1
                """, twoSellers ? 20002 : 10001);
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        context(true, null, 1, null);
        if (twoSellers) {
            repository.update("""
                    INSERT INTO seller_order(order_id,seller_order_no,store_id_ref,supplier_id_ref,
                    store_name_snapshot,supplier_name_snapshot,subtotal,total_amount,status)
                    VALUES(1,'SELLER-SECOND',2,21,'Second store','Second supplier',10001,10001,'AWAITING_PAYMENT')
                    """);
            transaction.executeWithoutResult(status -> integration.syncOrder(
                    new FinanceOrderContext(2, 10, 21, 10001, null, null, true, 1)));
        }
        var link = payments.initiate(1, key(), BUYER).payment();
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(link.orderCode(), link.amount(),
                link.linkId(), "retail-paid", "VND"));
        finance.webhook(new Webhook());
    }

    private void refundInFull(FinanceService finance, long sellerOrderId) {
        long paymentId = repository.payments(sellerOrderId).stream().filter(p -> "SUCCESS".equals(p.status()))
                .findFirst().orElseThrow().id();
        finance.requestRefund(sellerOrderId, paymentId, repository.paid(sellerOrderId), "Hoàn tiền hủy đơn",
                "cancel-refund-v1", key(), ADMIN);
        finance.prepareRefundTransfer(sellerOrderId, repository.refunds(sellerOrderId).getLast().id(), key(), ADMIN);
        finance.confirmRefund(sellerOrderId, repository.refunds(sellerOrderId).getLast().id(),
                "bank-refund-" + sellerOrderId, "https://proof.test/refund", key(), ADMIN);
    }

    private long restoredStockMarks() {
        return repository.count("SELECT COUNT(*) FROM customer_order WHERE order_id=1 AND stock_restored_at IS NOT NULL");
    }

    @Test
    void paidRetailCancellationIsApprovedOnlyAfterConfirmedFullRefundAndRestoresStock() {
        var catalog = restoringCatalog();
        var payments = retailPayments(catalog);
        var finance = financeWith(payments);
        paidRetailCheckout(payments, finance, false);
        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        finance.requestCancellation(1, "Shop báo hết hàng", key(), BUYER);
        long cancellationId = repository.cancellations(1).getFirst().id();

        assertThatThrownBy(() -> finance.decideCancellation(1, cancellationId, true, "Duyệt hủy", key(), ADMIN))
                .hasMessage("PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");
        long paymentId = repository.payments(1).getFirst().id();
        finance.requestRefund(1, paymentId, 10001, "Hoàn tiền hủy đơn", "cancel-refund-v1", key(), ADMIN);
        // A refund still awaiting transfer proof does not cover the collection yet.
        assertThatThrownBy(() -> finance.decideCancellation(1, cancellationId, true, "Duyệt hủy", key(), ADMIN))
                .hasMessage("PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");
        org.mockito.Mockito.verifyNoInteractions(catalog);
        finance.prepareRefundTransfer(1, repository.refunds(1).getFirst().id(), key(), ADMIN);
        finance.confirmRefund(1, repository.refunds(1).getFirst().id(), "bank-refund-1",
                "https://proof.test/refund", key(), ADMIN);

        finance.decideCancellation(1, cancellationId, true, "Duyệt hủy", key(), ADMIN);

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.cancellations(1).getFirst().status()).isEqualTo("APPROVED");
        verify(catalog, times(1)).cancelConfirmedStockReservation("checkout-stock");
        assertThat(restoredStockMarks()).isEqualTo(1);
        payments.recoverCancelledRetailStock();
        verify(catalog, times(1)).cancelConfirmedStockReservation("checkout-stock");
    }

    private void orderItem(long sellerOrderId, long variantId, int quantity) {
        repository.update("""
                INSERT INTO order_item(seller_order_id,variant_id_ref,product_name_snapshot,quantity,unit_price,subtotal)
                VALUES(?,?,'Bánh',?,1000,?)
                """, sellerOrderId, variantId, quantity, 1000L * quantity);
    }

    private long sellerStockRestored(long sellerOrderId) {
        return repository.count("SELECT COUNT(*) FROM seller_order WHERE seller_order_id=? AND stock_restored_at IS NOT NULL",
                sellerOrderId);
    }

    private static StockRestoreResponse restoredLines(String reservationKey, StockRestoreRequest request) {
        return new StockRestoreResponse(request.restoreKey(), reservationKey, "CONFIRMED", java.util.List.of());
    }

    /** Two paid seller orders: #1 holds variants 501 (2+1 units) and 502 (1 unit), #2 holds variant 601 (3 units). */
    private void paidTwoSellerCheckoutWithItems(CustomerPaymentServiceImpl payments, FinanceService finance) {
        paidRetailCheckout(payments, finance, true);
        orderItem(1, 501, 2);
        orderItem(1, 502, 1);
        orderItem(1, 501, 1);
        orderItem(2, 601, 3);
    }

    private void cancelPaid(FinanceService finance, long sellerOrderId) {
        finance.requestCancellation(sellerOrderId, "Hủy đơn shop " + sellerOrderId, key(), BUYER);
        refundInFull(finance, sellerOrderId);
        finance.decideCancellation(sellerOrderId, repository.cancellations(sellerOrderId).getFirst().id(), true,
                "Duyệt", key(), ADMIN);
    }

    @Test
    void partialRetailCancellationRestoresExactlyThatSellerOrdersLines() {
        var catalog = restoringCatalog();
        when(catalog.restoreStockReservationLines(eq("checkout-stock"), any()))
                .thenAnswer(call -> restoredLines(call.getArgument(0), call.getArgument(1)));
        var payments = retailPayments(catalog);
        var finance = financeWith(payments);
        paidTwoSellerCheckoutWithItems(payments, finance);

        cancelPaid(finance, 1);

        var restore = org.mockito.ArgumentCaptor.forClass(StockRestoreRequest.class);
        verify(catalog, times(1)).restoreStockReservationLines(eq("checkout-stock"), restore.capture());
        assertThat(restore.getValue().restoreKey()).isEqualTo("checkout-stock-so1");
        assertThat(restore.getValue().lines()).containsExactly(new StockReservationRequest.Line(501L, 3),
                new StockReservationRequest.Line(502L, 1));
        verify(catalog, never()).cancelConfirmedStockReservation(anyString());
        assertThat(sellerStockRestored(1)).isEqualTo(1);
        assertThat(sellerStockRestored(2)).isZero();
        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE status='READY_FOR_FULFILLMENT'"))
                .isEqualTo(1);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM finance_audit WHERE seller_order_id=1 AND event_type='STOCK_RESTORED'
                """)).isEqualTo(1);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_audit WHERE event_type='STOCK_RESTORE_DEFERRED'"))
                .isZero();
        payments.recoverCancelledRetailStock();
        verify(catalog, times(1)).restoreStockReservationLines(anyString(), any());

        // The last seller order closes the checkout: the whole reservation returns what is still sold (#2's lines).
        cancelPaid(finance, 2);

        assertThat(repository.count("SELECT COUNT(*) FROM customer_order WHERE status='CANCELLED'")).isEqualTo(1);
        verify(catalog, times(1)).cancelConfirmedStockReservation("checkout-stock");
        verify(catalog, times(1)).restoreStockReservationLines(anyString(), any());
        assertThat(restoredStockMarks()).isEqualTo(1);
    }

    @Test
    void failedPerLineStockRestoreIsRetriedWithTheSameKeyByTheRecoveryJob() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        when(catalog.restoreStockReservationLines(eq("checkout-stock"), any()))
                .thenThrow(new IllegalStateException("catalog unavailable"))
                .thenAnswer(call -> restoredLines(call.getArgument(0), call.getArgument(1)));
        var payments = retailPayments(catalog);
        var finance = financeWith(payments);
        paidTwoSellerCheckoutWithItems(payments, finance);

        cancelPaid(finance, 1);

        assertThat(orders("CANCELLED")).isEqualTo(1);
        assertThat(sellerStockRestored(1)).isZero();
        payments.recoverCancelledRetailStock();
        assertThat(sellerStockRestored(1)).isEqualTo(1);
        payments.recoverCancelledRetailStock();
        verify(catalog, times(2)).restoreStockReservationLines(eq("checkout-stock"),
                argThat(request -> "checkout-stock-so1".equals(request.restoreKey())));
        verify(catalog, never()).cancelConfirmedStockReservation(anyString());
    }

    @Test
    void stockRestoreThatFailsAfterApprovalIsRetriedByTheRecoveryJob() {
        var catalog = mock(mss.mooncake.commerce.client.CatalogServiceClient.class);
        when(catalog.cancelConfirmedStockReservation("checkout-stock"))
                .thenThrow(new IllegalStateException("catalog unavailable")).thenReturn(cancelledStock());
        var payments = retailPayments(catalog);
        var finance = financeWith(payments);
        paidRetailCheckout(payments, finance, false);
        finance.requestCancellation(1, "Đổi ý", key(), BUYER);
        refundInFull(finance, 1);

        finance.decideCancellation(1, repository.cancellations(1).getFirst().id(), true, "Duyệt", key(), ADMIN);

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(restoredStockMarks()).isZero();
        payments.recoverCancelledRetailStock();
        assertThat(restoredStockMarks()).isEqualTo(1);
        verify(catalog, times(2)).cancelConfirmedStockReservation("checkout-stock");
    }

    @Test
    void unpaidRetailSellerOrderIsCancelledThroughTheWholeCheckoutNotByAdminApproval() {
        repository.update("""
                UPDATE customer_order SET source_type='RETAIL',rfq_id_ref=NULL,status='AWAITING_PAYMENT' WHERE order_id=1
                """);
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        context(true, null, 1, null);
        service.requestCancellation(1, "Shop xin hủy", key(), new FinanceService.Actor(202, null, 20L, false));
        long id = repository.cancellations(1).getFirst().id();

        assertThatThrownBy(() -> service.decideCancellation(1, id, true, "Duyệt", key(), ADMIN))
                .hasMessage("UNPAID_RETAIL_CANCEL_WHOLE_ORDER");
        assertThat(orders("AWAITING_PAYMENT")).isEqualTo(2);
    }

    @Test
    void rfqCancellationWithRefundedDepositIsApprovedAndClosesTheOrder() {
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        collect(deposit, "deposit-paid");
        service.requestCancellation(1, "Đổi ý sau khi đặt cọc", key(), BUYER);
        long id = repository.cancellations(1).getFirst().id();
        assertThatThrownBy(() -> service.decideCancellation(1, id, true, "Duyệt", key(), ADMIN))
                .hasMessage("PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");
        refundInFull(service, 1);

        service.decideCancellation(1, id, true, "Duyệt hủy sau khi hoàn cọc", key(), ADMIN);

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.cancellations(1).getFirst().status()).isEqualTo("APPROVED");
        long remaining = repository.payments(1).get(1).id();
        assertThatThrownBy(() -> service.initiate(remaining, key(), BUYER)).hasMessage("PAYMENT_BLOCKED_BY_CASE");
    }

    @Test
    void adminCancellationListTracksTheRefundAndARefundedCancellationCannotBeRejected() {
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        collect(deposit, "deposit-paid");
        service.requestCancellation(1, "Đổi ý sau khi đặt cọc", key(), BUYER);
        long id = repository.cancellations(1).getFirst().id();
        long paid = repository.paid(1);
        var cases = new mss.mooncake.commerce.controller.AdminCancellationController(new JdbcTemplate(datasource));
        assertThat(cases.pending()).singleElement().satisfies(c -> {
            assertThat(c.paidAmount()).isEqualTo(paid);
            assertThat(c.refundedAmount()).isZero();
            assertThat(c.pendingRefundAmount()).isZero();
        });

        service.requestRefund(1, deposit, paid, "Hoàn tiền hủy đơn", "Hủy đơn: hoàn 100% số đã thu", key(), ADMIN);
        assertThat(cases.pending().getFirst().pendingRefundAmount()).isEqualTo(paid);
        // Money is on its way back to the buyer: rejecting would leave a refunded order running.
        assertThatThrownBy(() -> service.decideCancellation(1, id, false, "Từ chối", key(), ADMIN))
                .hasMessage("CANCELLATION_REFUND_STARTED");

        long refund = repository.refunds(1).getLast().id();
        service.prepareRefundTransfer(1, refund, key(), ADMIN);
        service.confirmRefund(1, refund, "bank-refund-1", "https://proof.test/refund", key(), ADMIN);
        assertThat(cases.pending().getFirst()).satisfies(c -> {
            assertThat(c.refundedAmount()).isEqualTo(paid);
            assertThat(c.pendingRefundAmount()).isZero();
        });
        assertThatThrownBy(() -> service.decideCancellation(1, id, false, "Từ chối", key(), ADMIN))
                .hasMessage("CANCELLATION_REFUND_STARTED");

        service.decideCancellation(1, id, true, "Đã hoàn 100% tiền cọc", key(), ADMIN);
        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(cases.pending()).isEmpty();
    }

    @Test
    void aRejectedRefundNoLongerBlocksRejectingTheCancellation() {
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        collect(deposit, "deposit-paid");
        service.requestCancellation(1, "Đổi ý sau khi đặt cọc", key(), BUYER);
        long id = repository.cancellations(1).getFirst().id();
        service.requestRefund(1, deposit, repository.paid(1), "Hoàn tiền hủy đơn", "Hủy đơn: hoàn 100% số đã thu", key(), ADMIN);
        service.rejectRefund(1, repository.refunds(1).getLast().id(), "Tạo nhầm", key(), ADMIN);

        service.decideCancellation(1, id, false, "Đơn đã được chuẩn bị", key(), ADMIN);

        assertThat(repository.cancellations(1).getFirst().status()).isEqualTo("REJECTED");
    }

    // --- Deposit kept for the supplier once preparation started (policy chốt 11/11/2026) ---

    private long paidDeposit() {
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        collect(deposit, "deposit-paid");
        return deposit;
    }

    private long retained() {
        return repository.count("SELECT COALESCE(SUM(retained_amount),0) FROM cancellation WHERE seller_order_id=1");
    }

    @Test
    void buyerCancellingAfterPreparationStartedKeepsTheDepositForTheSupplier() {
        paidDeposit();
        repository.update("UPDATE seller_order SET status='PREPARING' WHERE seller_order_id=1");
        service.requestCancellation(1, "Đổi ý khi nhà bán đang làm", key(), BUYER);
        long id = repository.cancellations(1).getFirst().id();
        var cases = new mss.mooncake.commerce.controller.AdminCancellationController(new JdbcTemplate(datasource));
        assertThat(cases.pending().getFirst().retainedAmount()).isEqualTo(3000);

        // Only the deposit was paid and it stays with the supplier: nothing to refund before approving.
        service.decideCancellation(1, id, true, "Người mua hủy khi nhà bán đã chuẩn bị", key(), ADMIN);
        assertThat(orders("CANCELLED")).isEqualTo(2);

        var supplier = new FinanceService.Actor(202, null, 20L, false);
        assertThat(service.supplierPayouts(supplier, 0, 20).content().getFirst()).satisfies(row -> {
            assertThat(row.payoutStatus()).isEqualTo("READY");
            assertThat(row.netPayout()).isEqualTo(2850);
        });
        assertThat(service.summary(1, ADMIN).eligibility().eligible()).isTrue();
        service.createSettlement(1, key(), ADMIN);
        var settlement = repository.settlements(1).getFirst();
        assertThat(settlement.grossAmount()).isEqualTo(3000);
        assertThat(settlement.feeAmount()).isEqualTo(150);
        assertThat(settlement.supplierPayoutAmount()).isEqualTo(2850);
        service.beginPayout(1, settlement.settlementId(), key(), ADMIN);
        service.confirmPayout(1, settlement.settlementId(), "bank-payout-deposit", "https://proof.test/payout", key(), ADMIN);
        assertThat(repository.settlements(1).getFirst().status()).isEqualTo("SUCCESS");
        assertThat(orders("CANCELLED")).isEqualTo(2);
    }

    @Test
    void buyerCancellingBeforePreparationStartedGetsTheDepositBack() {
        paidDeposit();
        service.requestCancellation(1, "Đổi ý trước khi nhà bán làm", key(), BUYER);

        assertThat(retained()).isZero();
        assertThatThrownBy(() -> service.decideCancellation(1, repository.cancellations(1).getFirst().id(), true,
                "Duyệt", key(), ADMIN)).hasMessage("PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");
    }

    @Test
    void supplierCancellingNeverKeepsTheDeposit() {
        paidDeposit();
        repository.update("UPDATE seller_order SET status='PREPARING' WHERE seller_order_id=1");

        service.requestCancellation(1, "Nhà bán không làm kịp", key(), new FinanceService.Actor(202, null, 20L, false));

        assertThat(retained()).isZero();
        assertThatThrownBy(() -> service.decideCancellation(1, repository.cancellations(1).getFirst().id(), true,
                "Duyệt", key(), ADMIN)).hasMessage("PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");
    }

    @Test
    void buyerCancellingAFullyPaidOrderInPreparationGetsBackEverythingButTheDeposit() {
        var netService = new FinanceServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                "NET_AFTER_REFUND", "HALF_UP", true, customerPayments, integration, 7, CommerceNotifier.disabled(), identity);
        paidDeposit();
        repository.update("UPDATE seller_order SET status='PREPARING' WHERE seller_order_id=1");
        long remaining = repository.payments(1).get(1).id();
        netService.initiate(remaining, key(), BUYER);
        collect(remaining, "remaining-paid");
        netService.requestCancellation(1, "Đổi ý khi nhà bán đang làm", key(), BUYER);
        long id = repository.cancellations(1).getFirst().id();
        assertThatThrownBy(() -> netService.decideCancellation(1, id, true, "Duyệt", key(), ADMIN))
                .hasMessage("PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");

        netService.requestRefund(1, remaining, 7001, "Hoàn phần đã trả thêm", "Giữ cọc cho nhà bán", key(), ADMIN);
        long refund = repository.refunds(1).getLast().id();
        netService.prepareRefundTransfer(1, refund, key(), ADMIN);
        netService.confirmRefund(1, refund, "bank-refund-balance", "https://proof.test/refund", key(), ADMIN);
        netService.decideCancellation(1, id, true, "Giữ cọc, hoàn phần còn lại", key(), ADMIN);

        netService.createSettlement(1, key(), ADMIN);
        var settlement = repository.settlements(1).getFirst();
        assertThat(settlement.grossAmount()).isEqualTo(10001);
        assertThat(settlement.refundedAmount()).isEqualTo(7001);
        assertThat(settlement.feeAmount()).isEqualTo(150);
        assertThat(settlement.supplierPayoutAmount()).isEqualTo(2850);
    }

    @Test
    void adminMayStillRefundTheWholeDepositAndThenNothingIsPaidOut() {
        long deposit = paidDeposit();
        repository.update("UPDATE seller_order SET status='PREPARING' WHERE seller_order_id=1");
        service.requestCancellation(1, "Nhà bán giao trễ", key(), BUYER);
        refundInFull(service, 1);

        service.decideCancellation(1, repository.cancellations(1).getFirst().id(), true, "Lỗi do nhà bán", key(), ADMIN);

        assertThat(repository.refunded(1)).isEqualTo(repository.payment(deposit).amount());
        assertThat(service.summary(1, ADMIN).eligibility().blockingReasons()).contains("FULLY_REFUNDED");
        assertThatThrownBy(() -> service.createSettlement(1, key(), ADMIN)).hasMessage("SETTLEMENT_NOT_ELIGIBLE");
    }

    private static final java.time.ZoneId VIETNAM = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    /** Balance due 00:00 Vietnam time three days before delivery: delivery in 3 days means it was due today. */
    private void balanceDue(boolean passed) {
        repository.update("UPDATE finance_context SET delivery_date=? WHERE seller_order_id=1",
                LocalDate.now(VIETNAM).plusDays(passed ? 3 : 4));
    }

    @Test
    void overdueBalanceCancelsTheOrderAndKeepsTheDepositForTheSupplier() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        paidDeposit();
        balanceDue(true);

        cancellation.cancelOverdueRemainingRfqOrders();
        cancellation.cancelOverdueRemainingRfqOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.cancellations(1)).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("APPROVED"));
        assertThat(retained()).isEqualTo(3000);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM finance_audit WHERE seller_order_id=1
                AND event_type='CANCELLATION_DECIDED' AND actor='SYSTEM'
                """)).isEqualTo(1);
        assertThat(service.summary(1, ADMIN).eligibility().eligible()).isTrue();
    }

    @Test
    void balanceNotYetDueLeavesTheOrderRunning() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        paidDeposit();
        balanceDue(false);

        cancellation.cancelOverdueRemainingRfqOrders();

        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
    }

    @Test
    void balancePaidAtTheDeadlineIsCollectedInsteadOfCancelling() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        paidDeposit();
        long remaining = repository.payments(1).get(1).id();
        service.initiate(remaining, key(), BUYER);
        var link = repository.payment(remaining);
        when(provider.cancel(link.orderCode())).thenReturn(new PaymentProvider.Status(link.orderCode(),
                link.amount(), link.amount(), link.linkId(), "PAID", "balance-at-deadline"));
        balanceDue(true);

        cancellation.cancelOverdueRemainingRfqOrders();

        assertThat(repository.paid(1)).isEqualTo(10001);
        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
    }

    @Test
    void rejectedCancellationCanBeRequestedAgainButOnlyOneCaseIsOpen() {
        rfqOrder(3000L, Instant.now());
        service.requestCancellation(1, "Lần thứ nhất", key(), BUYER);
        service.decideCancellation(1, repository.cancellations(1).getFirst().id(), false, "Chưa đủ căn cứ", key(), ADMIN);

        service.requestCancellation(1, "Lần thứ hai", key(), BUYER);

        assertThat(repository.cancellations(1)).extracting(c -> c.status()).containsExactly("REJECTED", "REQUESTED");
        assertThatThrownBy(() -> service.requestCancellation(1, "Lần thứ ba", key(), BUYER))
                .hasMessage("CANCELLATION_ALREADY_EXISTS");
    }

    @Test
    void unpaidOrderCanStillBeCancelledByTheBuyerAfterARejectedRequest() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(3000L, Instant.now());
        service.requestCancellation(1, "Xin hủy", key(), BUYER);
        service.decideCancellation(1, repository.cancellations(1).getFirst().id(), false, "Từ chối", key(), ADMIN);

        cancellation.cancelUnpaidOrder(1, "Tự hủy đơn chưa thanh toán", BUYER);

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.cancellations(1)).extracting(c -> c.status()).containsExactly("REJECTED", "APPROVED");
    }

    @Test
    void overdueRfqOrderWithARejectedCancellationIsStillAutoCancelled() {
        var cancellation = rfqCancellation(mock(mss.mooncake.commerce.client.CatalogServiceClient.class));
        rfqOrder(3000L, Instant.now().minus(java.time.Duration.ofHours(49)));
        service.requestCancellation(1, "Xin hủy", key(), BUYER);
        service.decideCancellation(1, repository.cancellations(1).getFirst().id(), false, "Từ chối", key(), ADMIN);

        cancellation.cancelOverdueRfqOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
    }

    @Test
    void retailSellerOrderPaymentCannotBeInitiatedOnItsOwn() {
        retail();
        context(true, null, 1, null);
        long id = repository.payments(1).getFirst().id();

        assertThatThrownBy(() -> service.initiate(id, key(), BUYER))
                .isInstanceOfSatisfying(mss.mooncake.commerce.exception.FinanceException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT))
                .hasMessage("RETAIL_CUSTOMER_ORDER_PAYMENT_REQUIRED");
        assertThat(repository.payment(id).orderCode()).isNull();
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void unknownSellerOrderFinanceSummaryIsNotFound() {
        assertThatThrownBy(() -> service.summary(999999, ADMIN))
                .isInstanceOfSatisfying(mss.mooncake.commerce.exception.FinanceException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND))
                .hasMessage("ORDER_NOT_FOUND");
    }

    // --- Non-delivery recourse without an agreed delivery date (QA B2) ---

    private void paidDaysAgo(int days) {
        repository.update("UPDATE payment SET paid_at=? WHERE seller_order_id=1 AND status='SUCCESS'",
                FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(days))));
    }

    @Test
    void retailBuyerMayOpenANonDeliveryDisputeOnceTheShippingSlaHasPassed() {
        var payments = retailPayments(restoringCatalog());
        paidRetailCheckout(payments, financeWith(payments), false);
        paidDaysAgo(8);

        service.openDispute(1, "Chưa nhận được hàng", null, key(), BUYER);

        assertThat(repository.disputes(1)).extracting(c -> c.status()).containsExactly("OPEN");
    }

    @Test
    void retailNonDeliveryDisputeWaitsForTheShippingSla() {
        var payments = retailPayments(restoringCatalog());
        paidRetailCheckout(payments, financeWith(payments), false);
        paidDaysAgo(6);

        assertThatThrownBy(() -> service.openDispute(1, "Chưa nhận được hàng", null, key(), BUYER))
                .hasMessage("DELIVERY_NOT_OVERDUE");
    }

    @Test
    void aLaterSupplierEstimateDoesNotPostponeTheShippingSla() {
        var payments = retailPayments(restoringCatalog());
        paidRetailCheckout(payments, financeWith(payments), false);
        paidDaysAgo(8);
        repository.update("""
                INSERT INTO shipment(seller_order_id,shipping_method,status,expected_delivery_at) VALUES(1,'STANDARD','SHIPPED',?)
                """, FinanceRepository.utc(Instant.now().plus(java.time.Duration.ofDays(30))));

        service.openDispute(1, "Chưa nhận được hàng", null, key(), BUYER);

        assertThat(repository.disputes(1)).hasSize(1);
    }

    @Test
    void agreedRfqDeliveryDateStillGovernsNonDeliveryDisputes() {
        fullPayment();
        paidDaysAgo(8);

        assertThatThrownBy(() -> service.openDispute(1, "Chưa nhận được hàng", null, key(), BUYER))
                .hasMessage("DELIVERY_NOT_OVERDUE");
    }

    @Test
    void cancelledShipmentEstimateIsNotADeliveryPromise() {
        fullPayment();
        repository.update("""
                INSERT INTO shipment(seller_order_id,shipping_method,status,expected_delivery_at) VALUES(1,'STANDARD','CANCELLED',?)
                """, FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(1))));

        assertThatThrownBy(() -> service.openDispute(1, "Chưa nhận được hàng", null, key(), BUYER))
                .hasMessage("DELIVERY_NOT_OVERDUE");
    }

    @Test
    void undisputedDeliveriesNobodyAcceptedBecomeAutoAcceptCandidatesAfterTheWindow() {
        fullPayment();
        repository.update("UPDATE seller_order SET status='DELIVERED' WHERE seller_order_id=1");
        transaction.executeWithoutResult(status -> integration.syncFulfillment(1, 1, true, false));
        repository.update("""
                INSERT INTO shipment(seller_order_id,shipping_method,status,delivered_at) VALUES(1,'STANDARD','DELIVERED',?)
                """, FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(8))));
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(7));

        assertThat(repository.autoAcceptableSellerOrderIds(cutoff)).containsExactly(1L);

        repository.update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(6))));
        assertThat(repository.autoAcceptableSellerOrderIds(cutoff)).isEmpty();

        repository.update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(8))));
        repository.update("""
                INSERT INTO dispute(seller_order_id,opened_by_user_id_ref,reason,status,opened_at) VALUES(1,101,'Hỏng',?,?)
                """, "RESOLVED", FinanceRepository.utc(Instant.now().minus(java.time.Duration.ofDays(2))));
        assertThat(repository.autoAcceptableSellerOrderIds(cutoff)).isEmpty();
    }

    @Test
    void shippingSlaIsConfigurable() {
        var payments = retailPayments(restoringCatalog());
        paidRetailCheckout(payments, financeWith(payments), false);
        paidDaysAgo(4);
        var threeDaySla = new FinanceServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                "TOTAL_COLLECTED", "HALF_UP", true, payments, integration, 3, CommerceNotifier.disabled(), identity);

        threeDaySla.openDispute(1, "Chưa nhận được hàng", null, key(), BUYER);

        assertThat(repository.disputes(1)).hasSize(1);
    }

    // --- Unpaid retail checkouts expire and return their stock (QA M1) ---

    private void unpaidRetailCheckout(Instant placedAt) {
        repository.update("""
                UPDATE customer_order SET source_type='RETAIL',rfq_id_ref=NULL,status='AWAITING_PAYMENT',
                stock_reservation_key='checkout-stock',placed_at=? WHERE order_id=1
                """, FinanceRepository.utc(placedAt));
        repository.update("UPDATE seller_order SET status='AWAITING_PAYMENT' WHERE seller_order_id=1");
        context(true, null, 1, null);
    }

    private FinanceRepository.CustomerPayment openRetailLink(CustomerPaymentServiceImpl payments, long remotePaid) {
        var link = payments.initiate(1, key(), BUYER).payment();
        when(provider.cancel(link.orderCode())).thenReturn(new PaymentProvider.Status(link.orderCode(),
                link.amount(), remotePaid, link.linkId(), remotePaid > 0 ? "PAID" : "CANCELLED",
                remotePaid > 0 ? "deadline-paid" : null));
        return repository.customerPayments(1).getFirst();
    }

    @Test
    void unpaidRetailOrderPastThePaymentTimeoutIsCancelledBySystemAndRestoresStock() {
        var catalog = restoringCatalog();
        var cancellation = rfqCancellation(catalog);
        unpaidRetailCheckout(Instant.now().minus(java.time.Duration.ofMinutes(61)));
        var link = openRetailLink(cancellation, 0);

        cancellation.cancelOverdueRetailOrders();
        cancellation.cancelOverdueRetailOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.count("""
                SELECT COUNT(*) FROM cancellation WHERE seller_order_id=1 AND status='APPROVED'
                AND actor_type='SYSTEM' AND actor_id_ref=0 AND reason=?
                """, "Quá hạn thanh toán 60 phút")).isEqualTo(1);
        assertThat(repository.customerPayment(link.id()).status()).isEqualTo("CANCELLED");
        verify(provider, times(1)).cancel(link.orderCode());
        verify(catalog, times(1)).cancelConfirmedStockReservation("checkout-stock");
        assertThat(restoredStockMarks()).isEqualTo(1);
    }

    @Test
    void unpaidRetailOrderWithinThePaymentTimeoutIsKept() {
        var catalog = restoringCatalog();
        var cancellation = rfqCancellation(catalog);
        unpaidRetailCheckout(Instant.now().minus(java.time.Duration.ofMinutes(59)));

        cancellation.cancelOverdueRetailOrders();

        assertThat(orders("AWAITING_PAYMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(catalog, provider);
    }

    @Test
    void retailPaymentCollectedAtTheDeadlineIsKeptInsteadOfCancelled() {
        var catalog = restoringCatalog();
        var cancellation = rfqCancellation(catalog);
        unpaidRetailCheckout(Instant.now().minus(java.time.Duration.ofMinutes(61)));
        openRetailLink(cancellation, 10001);

        cancellation.cancelOverdueRetailOrders();

        assertThat(repository.paid(1)).isEqualTo(10001);
        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        assertThat(repository.cancellations(1)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void retailPaymentTimeoutIsConfigurable() {
        var catalog = restoringCatalog();
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<FinanceService> finance =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(finance.getObject()).thenAnswer(call -> service);
        var cancellation = new CustomerPaymentServiceImpl(repository, provider,
                new DataSourceTransactionManager(datasource), catalog, finance, integration, 48, 15);
        unpaidRetailCheckout(Instant.now().minus(java.time.Duration.ofMinutes(16)));

        cancellation.cancelOverdueRetailOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
        assertThat(repository.cancellations(1).getFirst().reason()).isEqualTo("Quá hạn thanh toán 15 phút");
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
                    repos.getRepository(mss.mooncake.commerce.repository.OrderItemRepository.class),
                    repos.getRepository(mss.mooncake.commerce.repository.ShipmentLineRepository.class), repository, integration);
            repository.update("""
                    INSERT INTO order_item(seller_order_id,variant_id_ref,product_name_snapshot,quantity,unit_price,subtotal)
                    VALUES(1,501,'Cake box',2,5000.5,10001)
                    """);
            repository.update("UPDATE seller_order SET status='AWAITING_DEPOSIT' WHERE seller_order_id=1");
            context(true, 3000L, 1, LocalDate.now().plusDays(30));
            long deposit = repository.payments(1).getFirst().id();
            service.initiate(deposit, key(), BUYER);
            collect(deposit, "jpa-deposit");
            var shipment = tx.execute(s -> fulfillment.createShipment(
                    new mss.mooncake.commerce.dto.request.CreateShipmentRequest(1L, "STANDARD", "TEST", "TRACK",
                            null, null, java.math.BigDecimal.ZERO), 20L));
            assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='PREPARING'")).isEqualTo(1);
            // Without lines the batch carries every unit of the order (V12 shipment_line on the real schema).
            assertThat(shipment.lines()).extracting(l -> l.quantity()).containsExactly(2);
            assertThat(repository.count("SELECT COALESCE(SUM(quantity),0) FROM shipment_line")).isEqualTo(2);
            assertThatThrownBy(() -> tx.execute(s -> fulfillment.markAsShipped(shipment.shipmentId(), 20L)))
                    .hasMessageContaining("chưa thanh toán đủ");
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
            assertThat(service.summary(1, BUYER).eligibility().blockingReasons()).contains("DISPUTE_WINDOW_OPEN");
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
        retail();
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
                .hasMessage("RETAIL_CUSTOMER_ORDER_PAYMENT_REQUIRED");

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
        new JdbcTemplate(datasource).update("""
                INSERT INTO shipment(seller_order_id,shipping_method,status,delivered_at)
                VALUES(1,'STANDARD','DELIVERED',?)
                """, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(8)));
    }

    private void freshDelivery() {
        deliver();
        new JdbcTemplate(datasource).update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
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
        retail();
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
        prepareRefund(1, id);
        String key = key();
        service.confirmRefund(1, id, "bank-refund-1", "https://evidence.test/1", key, ADMIN);
        service.confirmRefund(1, id, "bank-refund-1", "https://evidence.test/1", key, ADMIN);
        assertThat(repository.count("SELECT COUNT(*) FROM finance_ledger WHERE entry_type='REFUNDED'")).isEqualTo(1);
    }

    @Test
    void refundTransferQrUsesFrozenRecipientAndExactAmount() {
        long paymentId = fullPayment();
        service.requestRefund(1, paymentId, 1000, "Damaged cake", "manual-v1", key(), ADMIN);
        long refundId = repository.refunds(1).getFirst().id();
        assertThatThrownBy(() -> service.confirmRefund(1, refundId, "bank-1", "https://proof.test/1", key(), ADMIN))
                .hasMessage("REFUND_DESTINATION_REQUIRED");
        service.prepareRefundTransfer(1, refundId, key(), ADMIN);
        var transfer = service.transferInstructions(1, ADMIN).transfers().getFirst();
        assertThat(transfer.amount()).isEqualTo(1000);
        assertThat(transfer.accountNumber()).isEqualTo("123456789");
        assertThat(transfer.qrImageUrl()).contains("amount=1000&addInfo=MSSREFUND" + refundId);
        when(identity.getCustomerPayoutAccount(10L)).thenReturn(
                new PayoutAccountResponse("CUSTOMER", "970422", "MBBank", "111111111", "Changed"));
        service.prepareRefundTransfer(1, refundId, key(), ADMIN);
        assertThat(service.transferInstructions(1, ADMIN).transfers().getFirst().accountNumber()).isEqualTo("123456789");
        assertThatThrownBy(() -> service.transferInstructions(1, BUYER)).hasMessage("FINANCE_ADMIN_REQUIRED");
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
        var transfer = service.transferInstructions(1, ADMIN).transfers().getFirst();
        assertThat(transfer.amount()).isEqualTo(9501);
        assertThat(transfer.accountNumber()).isEqualTo("987654321");
        assertThat(transfer.qrImageUrl()).contains("amount=9501&addInfo=MSSPAYOUT" + settlement.settlementId());
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
    void openDisputeBlocksSettlementDuringSevenDayWindow() {
        fullPayment(); freshDelivery();
        service.openDispute(1, "Damaged goods", "https://evidence.test/photo", key(), BUYER);
        assertThatThrownBy(() -> service.createSettlement(1, key(), ADMIN)).hasMessage("SETTLEMENT_NOT_ELIGIBLE");
        assertThat(service.summary(1, BUYER).eligibility().blockingReasons()).contains("OPEN_DISPUTE");
    }

    @Test
    void legacyReadySettlementIsHeldAndRepricedAfterDispute() {
        fullPayment(); deliver();
        service.createSettlement(1, key(), ADMIN);
        long settlementId = repository.settlements(1).getFirst().settlementId();
        new JdbcTemplate(datasource).update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        service.openDispute(1, "Late damage report", null, key(), BUYER);
        assertThat(repository.settlements(1).getFirst().status()).isEqualTo("ON_HOLD");
        assertThatThrownBy(() -> service.beginPayout(1, settlementId, key(), ADMIN))
                .hasMessage("SETTLEMENT_NOT_ELIGIBLE");
        long disputeId = repository.disputes(1).getFirst().id();
        service.respondDispute(1, disputeId, "Evidence reviewed", key(),
                new FinanceService.Actor(202, null, 20L, false));
        service.escalateDispute(1, disputeId, key(), BUYER);
        service.decideDispute(1, disputeId, "REJECT", null, "No defect found", key(), ADMIN);
        new JdbcTemplate(datasource).update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(8)));
        service.createSettlement(1, key(), ADMIN);
        assertThat(repository.settlements(1).getFirst().settlementId()).isEqualTo(settlementId);
        assertThat(repository.settlements(1).getFirst().status()).isEqualTo("READY");
    }

    @Test
    void partialRefundWaitsForProofThenSettlementUsesNetAmount() {
        long paymentId = fullPayment(); freshDelivery();
        service.openDispute(1, "Damaged goods", null, key(), BUYER);
        long disputeId = repository.disputes(1).getFirst().id();
        assertThatThrownBy(() -> service.escalateDispute(1, disputeId, key(), BUYER))
                .hasMessage("SELLER_RESPONSE_WINDOW_OPEN");
        assertThatThrownBy(() -> service.requestRefund(1, paymentId, 100, "reason", "policy", key(), ADMIN))
                .hasMessage("DISPUTE_REFUND_LINK_REQUIRED");
        var supplier = new FinanceService.Actor(202, null, 20L, false);
        service.respondDispute(1, disputeId, "Reviewed photos", key(), supplier);
        service.escalateDispute(1, disputeId, key(), BUYER);
        service.decideDispute(1, disputeId, "REFUND", 2000L, "Two cakes damaged", key(), ADMIN);
        service.requestRefund(1, paymentId, 2000, disputeId, "Award", "dispute-v1", key(), ADMIN);
        assertThat(repository.disputes(1).getFirst().status()).isEqualTo("REFUND_PENDING");
        long refundId = repository.refunds(1).getFirst().id();
        prepareRefund(1, refundId);
        service.confirmRefund(1, refundId, "bank-refund-2", "https://proof.test/2", key(), ADMIN);
        assertThat(repository.disputes(1).getFirst().status()).isEqualTo("RESOLVED");
        assertThatThrownBy(() -> service.openDispute(1, " damaged goods ", null, key(), BUYER))
                .hasMessage("DISPUTE_DUPLICATE");
        new JdbcTemplate(datasource).update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(8)));
        new JdbcTemplate(datasource).update("UPDATE finance_context SET received=0 WHERE seller_order_id=1");
        new JdbcTemplate(datasource).update("UPDATE seller_order SET status='DELIVERED' WHERE seller_order_id=1");
        var netService = new FinanceServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                "NET_AFTER_REFUND", "HALF_UP", true, customerPayments, integration, 7, CommerceNotifier.disabled(), identity);
        netService.createSettlement(1, key(), ADMIN);
        var settled = repository.settlements(1).getFirst();
        assertThat(settled.grossAmount()).isEqualTo(10001);
        assertThat(settled.refundedAmount()).isEqualTo(2000);
        assertThat(settled.feeAmount()).isEqualTo(400);
        assertThat(settled.supplierPayoutAmount()).isEqualTo(7601);
        netService.beginPayout(1, settled.settlementId(), key(), ADMIN);
        netService.confirmPayout(1, settled.settlementId(), "bank-payout-2", "https://proof.test/payout", key(), ADMIN);
        assertThat(repository.count("SELECT COUNT(*) FROM seller_order WHERE status='SETTLED'")).isEqualTo(1);
    }

    @Test
    void fullRefundLeavesNothingToPayOut() {
        long paymentId = fullPayment(); freshDelivery();
        service.openDispute(1, "All goods damaged", null, key(), BUYER);
        long disputeId = repository.disputes(1).getFirst().id();
        service.respondDispute(1, disputeId, "Reviewed", key(),
                new FinanceService.Actor(202, null, 20L, false));
        service.escalateDispute(1, disputeId, key(), BUYER);
        service.decideDispute(1, disputeId, "REFUND", 10001L, "Full refund", key(), ADMIN);
        service.requestRefund(1, paymentId, 10001, disputeId, "Award", "dispute-v1", key(), ADMIN);
        prepareRefund(1, repository.refunds(1).getFirst().id());
        service.confirmRefund(1, repository.refunds(1).getFirst().id(),
                "bank-full-refund", "https://proof.test/full", key(), ADMIN);
        new JdbcTemplate(datasource).update("UPDATE shipment SET delivered_at=? WHERE seller_order_id=1",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(8)));
        var netService = new FinanceServiceImpl(repository, provider, new DataSourceTransactionManager(datasource),
                "NET_AFTER_REFUND", "HALF_UP", true, customerPayments, integration, 7, CommerceNotifier.disabled(), identity);
        assertThat(netService.summary(1, BUYER).eligibility().blockingReasons()).contains("FULLY_REFUNDED");
        assertThatThrownBy(() -> netService.createSettlement(1, key(), ADMIN)).hasMessage("SETTLEMENT_NOT_ELIGIBLE");
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

    // --- Seller payout view (Doanh thu & giải ngân) ---

    private void sellerOrder(long expectedId, long supplierId, long total, String status) {
        long id = repository.insert("""
                INSERT INTO seller_order(order_id,seller_order_no,store_id_ref,supplier_id_ref,
                store_name_snapshot,supplier_name_snapshot,subtotal,total_amount,status)
                VALUES(1,?,1,?,'Store','Supplier',?,?,?)
                """, "SELLER-" + expectedId, supplierId, total, total, status);
        assertThat(id).isEqualTo(expectedId);
        transaction.executeWithoutResult(s -> integration.syncOrder(
                new FinanceOrderContext(id, 10, supplierId, total, null, null, true, 1)));
    }

    @Test
    void supplierSeesSettlementStateOfOwnSellerOrdersWithTotals() {
        var supplier = new FinanceService.Actor(202, null, 20L, false);
        fullPayment();
        deliver();
        sellerOrder(2, 20, 5000, "AWAITING_PAYMENT");
        sellerOrder(3, 21, 7000, "AWAITING_PAYMENT");

        var page = service.supplierPayouts(supplier, 0, 20);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content()).extracting(FinanceResponse.SupplierPayouts.Row::sellerOrderId).containsExactly(2L, 1L);
        var unpaid = page.content().getFirst();
        assertThat(unpaid.grossCollected()).isZero();
        assertThat(unpaid.payoutStatus()).isEqualTo("BLOCKED");
        assertThat(unpaid.blockingReasons()).contains("NOT_FULLY_PAID");
        var delivered = page.content().get(1);
        // Fee estimated with the configured settlement rule (5%, HALF_UP) before any settlement exists.
        assertThat(delivered).extracting(FinanceResponse.SupplierPayouts.Row::grossCollected,
                        FinanceResponse.SupplierPayouts.Row::refundedAmount, FinanceResponse.SupplierPayouts.Row::platformFee,
                        FinanceResponse.SupplierPayouts.Row::netPayout, FinanceResponse.SupplierPayouts.Row::feeEstimated,
                        FinanceResponse.SupplierPayouts.Row::eligible, FinanceResponse.SupplierPayouts.Row::payoutStatus)
                .containsExactly(10001L, 0L, 500L, 9501L, true, true, "READY");
        assertThat(page.totals()).isEqualTo(new FinanceResponse.SupplierPayouts.Totals(0, 9501, 0, 0));

        service.createSettlement(1, key(), ADMIN);
        long settlementId = repository.settlements(1).getFirst().settlementId();
        var ready = service.supplierPayouts(supplier, 0, 20).content().get(1);
        assertThat(ready.settlementId()).isEqualTo(settlementId);
        assertThat(ready.settlementStatus()).isEqualTo("READY");
        assertThat(ready.feeEstimated()).isFalse();
        service.beginPayout(1, settlementId, key(), ADMIN);
        assertThat(service.supplierPayouts(supplier, 0, 20).content().get(1).payoutStatus()).isEqualTo("PROCESSING");
        service.confirmPayout(1, settlementId, "bank-settlement-1", "https://evidence.test/payout", key(), ADMIN);

        var settled = service.supplierPayouts(supplier, 0, 1);
        assertThat(settled.content()).extracting(FinanceResponse.SupplierPayouts.Row::sellerOrderId).containsExactly(2L);
        assertThat(settled.totalPages()).isEqualTo(2);
        assertThat(settled.totals()).isEqualTo(new FinanceResponse.SupplierPayouts.Totals(0, 0, 0, 9501));
        var paid = service.supplierPayouts(supplier, 1, 1).content().getFirst();
        assertThat(paid.payoutStatus()).isEqualTo("PAID");
        assertThat(paid.netPayout()).isEqualTo(9501);
        assertThat(paid.payoutReference()).isEqualTo("bank-settlement-1");
        assertThat(paid.paidAt()).isNotNull();
    }

    @Test
    void cancelledOrUnsettledMoneyIsPendingAndOnlySuppliersSeeTheirPayouts() {
        var supplier = new FinanceService.Actor(202, null, 20L, false);
        fullPayment();
        // Paid but not delivered yet: the collected money waits for delivery and acceptance.
        var pending = service.supplierPayouts(supplier, 0, 20);
        assertThat(pending.content().getFirst().payoutStatus()).isEqualTo("BLOCKED");
        assertThat(pending.content().getFirst().netPayout()).isEqualTo(9501);
        assertThat(pending.totals()).isEqualTo(new FinanceResponse.SupplierPayouts.Totals(9501, 0, 0, 0));

        assertThatThrownBy(() -> service.supplierPayouts(BUYER, 0, 20)).hasMessage("SUPPLIER_REQUIRED");
        assertThatThrownBy(() -> service.supplierPayouts(ADMIN, 0, 20)).hasMessage("SUPPLIER_REQUIRED");
        assertThatThrownBy(() -> service.supplierPayouts(supplier, 0, 0)).hasMessage("INVALID_FINANCE_REQUEST");
        assertThatThrownBy(() -> service.supplierPayouts(supplier, -1, 20)).hasMessage("INVALID_FINANCE_REQUEST");
        assertThat(service.supplierPayouts(new FinanceService.Actor(203, null, 99L, false), 0, 20).content()).isEmpty();
    }

    @Test
    void adminListsEverySellerOrderPayoutNewestFirstAndFiltersByOrderNumber() {
        fullPayment();
        deliver();
        sellerOrder(2, 20, 5000, "AWAITING_PAYMENT");
        sellerOrder(3, 21, 7000, "AWAITING_PAYMENT");

        var first = service.adminPayouts(ADMIN, null, 0, 2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).extracting(FinanceResponse.SupplierPayouts.Row::sellerOrderId).containsExactly(3L, 2L);
        assertThat(service.adminPayouts(ADMIN, "", 1, 2).content().getFirst())
                .extracting(FinanceResponse.SupplierPayouts.Row::sellerOrderId,
                        FinanceResponse.SupplierPayouts.Row::netPayout, FinanceResponse.SupplierPayouts.Row::payoutStatus)
                .containsExactly(1L, 9501L, "READY");

        var byNumber = service.adminPayouts(ADMIN, " SELLER-3 ", 0, 20);
        assertThat(byNumber.totalElements()).isEqualTo(1);
        assertThat(byNumber.content()).extracting(FinanceResponse.SupplierPayouts.Row::sellerOrderNo).containsExactly("SELLER-3");
        // The customer order number matches too; LIKE wildcards typed by the admin are literal.
        assertThat(service.adminPayouts(ADMIN, "TEST", 0, 20).totalElements()).isEqualTo(3);
        assertThat(service.adminPayouts(ADMIN, "%", 0, 20).content()).isEmpty();

        assertThatThrownBy(() -> service.adminPayouts(new FinanceService.Actor(202, null, 20L, false), null, 0, 20))
                .hasMessage("FINANCE_ADMIN_REQUIRED");
        assertThatThrownBy(() -> service.adminPayouts(ADMIN, null, 0, 101)).hasMessage("INVALID_FINANCE_REQUEST");
        assertThatThrownBy(() -> service.adminPayouts(ADMIN, null, -1, 20)).hasMessage("INVALID_FINANCE_REQUEST");
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
        new JdbcTemplate(datasource).update("INSERT INTO shipment(seller_order_id,shipping_method,status,expected_delivery_at) "
                + "VALUES(1,'STANDARD','SHIPPED',?)",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1)));
        service.openDispute(1, "reason", null, key(), BUYER);
        long disputeId = repository.disputes(1).getFirst().id();
        assertThatThrownBy(() -> service.addEvidence(1, disputeId, "https://evidence.test/foreign", key(),
                new FinanceService.Actor(999, 999L, null, false))).hasMessage("FINANCE_ACCESS_DENIED");
        service.addEvidence(1, disputeId, "https://evidence.test/photo", key(), BUYER);
        assertThat(repository.history(1).getFirst().eventType()).isEqualTo("DISPUTE_EVIDENCE_ADDED");
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

    // --- In-app notifications: real finance flows, Identity client mocked ---

    private IdentityServiceClient identity;

    /** Rebuilds service and customerPayments with a notifier that posts to a mocked Identity client. */
    private CustomerPaymentServiceImpl notifying() {
        identity = mock(IdentityServiceClient.class);
        stubAccounts();
        var notifier = new CommerceNotifier(identity, repository);
        var manager = new DataSourceTransactionManager(datasource);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<FinanceService> finance =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(finance.getObject()).thenAnswer(call -> service);
        var payments = new CustomerPaymentServiceImpl(repository, provider, manager,
                mock(mss.mooncake.commerce.client.CatalogServiceClient.class), finance, integration, 48, 60, notifier);
        customerPayments = payments;
        service = new FinanceServiceImpl(repository, provider, manager, "TOTAL_COLLECTED", "HALF_UP", true,
                payments, integration, 7, notifier, identity);
        return payments;
    }

    private java.util.List<CreateNotificationRequest> notifications() {
        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity, org.mockito.Mockito.atLeast(0)).createNotification(captor.capture());
        return captor.getAllValues();
    }

    private CreateNotificationRequest notification(String dedupKey) {
        return notifications().stream().filter(n -> dedupKey.equals(n.dedupKey())).findFirst()
                .orElseThrow(() -> new AssertionError("No notification " + dedupKey + " in " + notifications()));
    }

    @Test
    void collectedDepositNotifiesTheSupplierAndTheBuyerExactlyOnce() {
        notifying();
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);

        collect(deposit, "deposit-paid");
        collect(deposit, "deposit-paid");

        var supplier = notification("PAYMENT_RECEIVED:" + deposit);
        assertThat(supplier.supplierId()).isEqualTo(20L);
        assertThat(supplier.customerId()).isNull();
        assertThat(supplier.title()).isEqualTo("Đã nhận tiền đặt cọc");
        assertThat(supplier.body()).contains("SELLER-TEST").contains("3.000 ₫");
        assertThat(supplier.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1", "role", "SELLER"));
        var buyer = notification("PAYMENT_CONFIRMED:" + deposit);
        assertThat(buyer.customerId()).isEqualTo(10L);
        assertThat(buyer.supplierId()).isNull();
        assertThat(buyer.body()).contains("TEST");
        assertThat(buyer.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1"));
        assertThat(notifications()).hasSize(2);
    }

    @Test
    void identityOutageNeverFailsPaymentCollection() {
        notifying();
        doThrow(new IllegalStateException("identity down")).when(identity).createNotification(any());
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);

        collect(deposit, "deposit-paid");

        assertThat(repository.payment(deposit).status()).isEqualTo("SUCCESS");
        assertThat(orders("READY_FOR_FULFILLMENT")).isEqualTo(2);
        verify(identity, times(2)).createNotification(any());
    }

    @Test
    void collectionRolledBackWithItsCallerNotifiesNobody() {
        notifying();
        rfqOrder(3000L, Instant.now());
        long deposit = repository.payments(1).getFirst().id();
        service.initiate(deposit, key(), BUYER);
        var p = repository.payment(deposit);
        when(provider.verify(any())).thenReturn(new PaymentProvider.Receipt(p.orderCode(), p.amount(), p.linkId(),
                "rolled-back", "VND"));

        transaction.executeWithoutResult(status -> {
            service.webhook(new Webhook());
            status.setRollbackOnly();
        });

        assertThat(repository.payment(deposit).status()).isNotEqualTo("SUCCESS");
        verify(identity, never()).createNotification(any());
    }

    @Test
    void checkoutPaymentIsConfirmedOnceToTheBuyerAndReportedToEachSupplier() {
        paidRetailCheckout(notifying(), service, true);

        long customerPayment = repository.customerPayments(1).getFirst().id();
        var buyer = notification("PAYMENT_CONFIRMED:CUSTOMER_PAYMENT:" + customerPayment);
        assertThat(buyer.customerId()).isEqualTo(10L);
        assertThat(buyer.body()).contains("20.002 ₫");
        assertThat(buyer.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1"));
        var first = notification("PAYMENT_RECEIVED:" + repository.payments(1).getFirst().id());
        assertThat(first.supplierId()).isEqualTo(20L);
        assertThat(first.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1", "role", "SELLER"));
        var second = notification("PAYMENT_RECEIVED:" + repository.payments(2).getFirst().id());
        assertThat(second.supplierId()).isEqualTo(21L);
        assertThat(second.target()).isEqualTo(Map.of("kind", "ORDER", "id", "2", "role", "SELLER"));
        assertThat(notifications()).hasSize(3);
    }

    @Test
    void cancellationRequestGoesToTheSupplierAndApprovalUsesTheRfqServiceKeys() {
        notifying();
        rfqOrder(3000L, Instant.now());
        service.requestCancellation(1, "Đổi kế hoạch sự kiện", key(), BUYER);
        long cancellationId = repository.cancellations(1).getFirst().id();

        var requested = notification("CANCELLATION_REQUESTED:" + cancellationId);
        assertThat(requested.supplierId()).isEqualTo(20L);
        assertThat(requested.body()).contains("Đổi kế hoạch sự kiện");

        service.decideCancellation(1, cancellationId, true, "Chấp nhận hủy", key(), ADMIN);

        var buyer = notification("ORDER_CANCELLED:1");
        assertThat(buyer.customerId()).isEqualTo(10L);
        assertThat(buyer.type()).isEqualTo("ORDER_CANCELLED");
        assertThat(buyer.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1"));
        var supplier = notification("SELLER_ORDER_CANCELLED:1");
        assertThat(supplier.supplierId()).isEqualTo(20L);
        assertThat(supplier.type()).isEqualTo("ORDER_CANCELLED");
        assertThat(supplier.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1", "role", "SELLER"));
        assertThat(notifications()).hasSize(3);
    }

    @Test
    void rejectedCancellationIsReportedToTheBuyer() {
        notifying();
        rfqOrder(3000L, Instant.now());
        service.requestCancellation(1, "Đổi ý", key(), BUYER);
        long cancellationId = repository.cancellations(1).getFirst().id();

        service.decideCancellation(1, cancellationId, false, "Đơn đã được chuẩn bị", key(), ADMIN);

        var rejected = notification("CANCELLATION_REJECTED:" + cancellationId);
        assertThat(rejected.customerId()).isEqualTo(10L);
        assertThat(rejected.body()).contains("Đơn đã được chuẩn bị");
        assertThat(notifications()).extracting(CreateNotificationRequest::type).doesNotContain("ORDER_CANCELLED");
    }

    @Test
    void systemCancelsAnOverdueRfqOrderAndTellsTheBuyerWhyOnce() {
        var payments = notifying();
        rfqOrder(3000L, Instant.now().minus(java.time.Duration.ofHours(49)));

        payments.cancelOverdueRfqOrders();
        payments.cancelOverdueRfqOrders();

        assertThat(orders("CANCELLED")).isEqualTo(2);
        var cancelled = notification("ORDER_CANCELLED:1");
        assertThat(cancelled.customerId()).isEqualTo(10L);
        assertThat(cancelled.body()).contains("TEST").contains("Quá hạn đặt cọc 48 giờ");
        assertThat(cancelled.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1"));
        assertThat(notifications()).hasSize(1);
    }

    @Test
    void disputeAndItsRefundNotifyTheRightSide() {
        notifying();
        long paymentId = fullPayment(); freshDelivery();
        service.openDispute(1, "Bánh bị vỡ", "https://evidence.test/photo", key(), BUYER);
        long disputeId = repository.disputes(1).getFirst().id();

        var opened = notification("DISPUTE_OPENED:" + disputeId);
        assertThat(opened.supplierId()).isEqualTo(20L);
        assertThat(opened.body()).contains("Bánh bị vỡ");

        service.respondDispute(1, disputeId, "Đã xem ảnh", key(), new FinanceService.Actor(202, null, 20L, false));
        service.escalateDispute(1, disputeId, key(), BUYER);
        service.decideDispute(1, disputeId, "REFUND", 4000L, "Hoàn hai bánh", key(), ADMIN);

        assertThat(notification("DISPUTE_RESOLVED:" + disputeId + ":CUSTOMER").customerId()).isEqualTo(10L);
        assertThat(notification("DISPUTE_RESOLVED:" + disputeId + ":SUPPLIER").supplierId()).isEqualTo(20L);
        assertThat(notification("DISPUTE_RESOLVED:" + disputeId + ":CUSTOMER").body()).contains("4.000 ₫");

        service.requestRefund(1, paymentId, 4000, disputeId, "Award", "dispute-v1", key(), ADMIN);
        long refundId = repository.refunds(1).getFirst().id();
        prepareRefund(1, refundId);
        service.confirmRefund(1, refundId, "bank-refund-1", "https://proof.test/refund", key(), ADMIN);

        var refund = notification("REFUND_CONFIRMED:" + refundId);
        assertThat(refund.customerId()).isEqualTo(10L);
        assertThat(refund.body()).contains("4.000 ₫");
    }

    @Test
    void confirmedPayoutNotifiesTheSupplier() {
        notifying();
        fullPayment(); deliver();
        service.createSettlement(1, key(), ADMIN);
        var settlement = repository.settlements(1).getFirst();
        service.beginPayout(1, settlement.settlementId(), key(), ADMIN);

        service.confirmPayout(1, settlement.settlementId(), "bank-settlement-1", "https://evidence.test/payout", key(), ADMIN);

        var paid = notification("PAYOUT_PAID:" + settlement.settlementId());
        assertThat(paid.supplierId()).isEqualTo(20L);
        assertThat(paid.body()).contains("9.501 ₫").contains("bank-settlement-1");
        assertThat(paid.target()).isEqualTo(Map.of("kind", "ORDER", "id", "1", "role", "SELLER"));
    }
}
