package mss.mooncake.commerce.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.Context;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPaymentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class CommerceNotifierTest {
    private static final long ORDER = 501;
    private static final long SELLER_ORDER = 601;
    private static final long BUYER = 101;
    private static final long SUPPLIER = 202;

    @Mock IdentityServiceClient identity;
    @Mock FinanceRepository orders;
    CommerceNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new CommerceNotifier(identity, orders);
        sellerOrder(SELLER_ORDER, SUPPLIER);
        lenient().when(orders.customerOrderNumber(ORDER)).thenReturn("MSS-20261108-501");
        lenient().when(orders.customerOrderOwner(ORDER)).thenReturn(BUYER);
        lenient().when(orders.sellerOrderIds(ORDER)).thenReturn(List.of(SELLER_ORDER));
    }

    private void sellerOrder(long id, long supplierId) {
        lenient().when(orders.context(id)).thenReturn(new Context(id, BUYER, supplierId, 10001, 3000L,
                LocalDate.now().plusDays(30), true, 1, 0, false, false));
        lenient().when(orders.parentOrderId(id)).thenReturn(ORDER);
        lenient().when(orders.sellerOrderNumber(id)).thenReturn("MSS-20261108-501-" + id);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Every request sent so far, each checked against Identity's validation rules. */
    private List<CreateNotificationRequest> sent() {
        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity, org.mockito.Mockito.atLeast(0)).createNotification(captor.capture());
        captor.getAllValues().forEach(CommerceNotifierTest::meetsIdentityContract);
        return captor.getAllValues();
    }

    private CreateNotificationRequest sent(String dedupKey) {
        return sent().stream().filter(r -> dedupKey.equals(r.dedupKey())).findFirst()
                .orElseThrow(() -> new AssertionError("No notification with key " + dedupKey + " in " + sent()));
    }

    private static void meetsIdentityContract(CreateNotificationRequest request) {
        assertThat(java.util.stream.Stream.of(request.userId(), request.customerId(), request.supplierId())
                .filter(java.util.Objects::nonNull)).hasSize(1);
        assertThat(request.type()).matches("[A-Z][A-Z0-9_]{0,49}");
        assertThat(request.title()).isNotBlank().hasSizeLessThanOrEqualTo(255);
        assertThat(request.body()).hasSizeLessThanOrEqualTo(1000);
        assertThat(request.target()).containsKey("kind").hasSizeLessThanOrEqualTo(10);
        assertThat((String) request.target().get("kind")).matches("[A-Z][A-Z0-9_]*");
        request.target().values().forEach(value -> assertThat(value).isInstanceOf(String.class)
                .asString().hasSizeLessThanOrEqualTo(100));
        assertThat(request.dedupKey()).isNotNull().hasSizeLessThanOrEqualTo(150).matches("[\\x20-\\x7E]+");
    }

    private static Map<String, Object> buyerOrder() {
        return Map.of("kind", "ORDER", "id", String.valueOf(ORDER));
    }

    private static Map<String, Object> sellerOrder() {
        return Map.of("kind", "ORDER", "id", String.valueOf(SELLER_ORDER), "role", "SELLER");
    }

    @Test
    void collectedDepositNotifiesTheSupplierAndConfirmsToTheBuyer() {
        notifier.paymentCollected(SELLER_ORDER, 41, "DEPOSIT", 3000);

        assertThat(sent()).hasSize(2);
        var supplier = sent("PAYMENT_RECEIVED:41");
        assertThat(supplier.supplierId()).isEqualTo(SUPPLIER);
        assertThat(supplier.type()).isEqualTo("PAYMENT_RECEIVED");
        assertThat(supplier.title()).isEqualTo("Đã nhận tiền đặt cọc");
        assertThat(supplier.body()).contains("3.000 ₫").contains("MSS-20261108-501-601");
        assertThat(supplier.target()).isEqualTo(sellerOrder());
        var buyer = sent("PAYMENT_CONFIRMED:41");
        assertThat(buyer.customerId()).isEqualTo(BUYER);
        assertThat(buyer.type()).isEqualTo("PAYMENT_CONFIRMED");
        assertThat(buyer.body()).contains("3.000 ₫").contains("MSS-20261108-501");
        assertThat(buyer.target()).isEqualTo(buyerOrder());
    }

    @Test
    void fullAndRemainingPaymentsAreNamedForTheSupplier() {
        notifier.paymentCollected(SELLER_ORDER, 42, "REMAINING", 7001);
        notifier.paymentCollected(SELLER_ORDER, 43, "FULL", 10001);

        assertThat(sent("PAYMENT_RECEIVED:42").title()).isEqualTo("Đã nhận thanh toán phần còn lại");
        assertThat(sent("PAYMENT_RECEIVED:43").title()).isEqualTo("Đã nhận thanh toán đủ");
        assertThat(sent("PAYMENT_RECEIVED:43").body()).contains("10.001 ₫");
    }

    @Test
    void checkoutPaymentIsConfirmedOnceToTheBuyerAndReportedToEverySupplier() {
        sellerOrder(602, 203);

        notifier.customerPaymentCollected(77, 20002, List.of(new CustomerPaymentItem(41, SELLER_ORDER, 10001, "S1"),
                new CustomerPaymentItem(42, 602, 10001, "S2")));

        assertThat(sent()).hasSize(3);
        var buyer = sent("PAYMENT_CONFIRMED:CUSTOMER_PAYMENT:77");
        assertThat(buyer.customerId()).isEqualTo(BUYER);
        assertThat(buyer.body()).contains("20.002 ₫").contains("MSS-20261108-501");
        assertThat(buyer.target()).isEqualTo(buyerOrder());
        assertThat(sent("PAYMENT_RECEIVED:41").supplierId()).isEqualTo(SUPPLIER);
        assertThat(sent("PAYMENT_RECEIVED:42").supplierId()).isEqualTo(203L);
        assertThat(sent("PAYMENT_RECEIVED:42").target()).containsEntry("id", "602");
    }

    @Test
    void shipmentsAreReportedToTheBuyerAndTheLastDeliveryAsksForAcceptance() {
        notifier.shipmentShipped(SELLER_ORDER, 9, "GHTK", "VN123456789");
        notifier.shipmentDelivered(SELLER_ORDER, 9, false);
        notifier.shipmentDelivered(SELLER_ORDER, 10, true);

        var shipped = sent("SHIPMENT_SHIPPED:9");
        assertThat(shipped.customerId()).isEqualTo(BUYER);
        assertThat(shipped.body()).contains("MSS-20261108-501").contains("GHTK").contains("VN123456789");
        assertThat(shipped.target()).isEqualTo(buyerOrder());
        assertThat(sent("SHIPMENT_DELIVERED:9").type()).isEqualTo("SHIPMENT_DELIVERED");
        assertThat(sent("SHIPMENT_DELIVERED:10").customerId()).isEqualTo(BUYER);
        var delivered = sent("ORDER_DELIVERED:601");
        assertThat(delivered.type()).isEqualTo("ORDER_DELIVERED");
        assertThat(delivered.body()).contains("nghiệm thu").contains("7 ngày");
        assertThat(delivered.target()).isEqualTo(buyerOrder());
        assertThat(sent()).hasSize(4);
    }

    @Test
    void acceptanceByTheBuyerOrTheSystemNotifiesTheSupplier() {
        notifier.orderAccepted(SELLER_ORDER, false);

        var accepted = sent("ORDER_ACCEPTED:601");
        assertThat(accepted.supplierId()).isEqualTo(SUPPLIER);
        assertThat(accepted.type()).isEqualTo("ORDER_ACCEPTED");
        assertThat(accepted.target()).isEqualTo(sellerOrder());

        notifier.orderAccepted(SELLER_ORDER, true);
        assertThat(sent().get(1).body()).contains("tự động");
    }

    @Test
    void approvedCancellationUsesTheOrderKeysRfqServiceUses() {
        notifier.cancellationDecided(SELLER_ORDER, 5, true, "Đồng ý hủy");

        var buyer = sent("ORDER_CANCELLED:501");
        assertThat(buyer.customerId()).isEqualTo(BUYER);
        assertThat(buyer.type()).isEqualTo("ORDER_CANCELLED");
        assertThat(buyer.target()).isEqualTo(buyerOrder());
        var supplier = sent("SELLER_ORDER_CANCELLED:601");
        assertThat(supplier.supplierId()).isEqualTo(SUPPLIER);
        assertThat(supplier.type()).isEqualTo("ORDER_CANCELLED");
        assertThat(supplier.target()).isEqualTo(sellerOrder());
    }

    @Test
    void cancellingOneStoreOfAMultiStoreCheckoutKeepsAKeyPerStoreForTheBuyer() {
        when(orders.sellerOrderIds(ORDER)).thenReturn(List.of(SELLER_ORDER, 602L));

        notifier.cancellationDecided(SELLER_ORDER, 5, true, null);

        assertThat(sent("ORDER_CANCELLED:501:601").customerId()).isEqualTo(BUYER);
        assertThat(sent("SELLER_ORDER_CANCELLED:601").supplierId()).isEqualTo(SUPPLIER);
    }

    @Test
    void requestGoesToTheSupplierUnlessTheSupplierAskedAndRejectionGoesToTheBuyer() {
        notifier.cancellationRequested(SELLER_ORDER, 5, "CUSTOMER", "Đổi kế hoạch");
        notifier.cancellationRequested(SELLER_ORDER, 6, "SUPPLIER", "Hết nguyên liệu");
        notifier.cancellationDecided(SELLER_ORDER, 5, false, "Đơn đã chuẩn bị xong");

        assertThat(sent()).hasSize(2);
        var requested = sent("CANCELLATION_REQUESTED:5");
        assertThat(requested.supplierId()).isEqualTo(SUPPLIER);
        assertThat(requested.body()).contains("Đổi kế hoạch").contains("MSS-20261108-501-601");
        assertThat(requested.target()).isEqualTo(sellerOrder());
        var rejected = sent("CANCELLATION_REJECTED:5");
        assertThat(rejected.customerId()).isEqualTo(BUYER);
        assertThat(rejected.body()).contains("Đơn đã chuẩn bị xong");
        assertThat(rejected.target()).isEqualTo(buyerOrder());
    }

    @Test
    void systemCancellationOfAnUnpaidOrderTellsTheBuyerWhy() {
        notifier.unpaidOrderCancelled(ORDER, "Quá hạn đặt cọc 48 giờ");

        var cancelled = sent("ORDER_CANCELLED:501");
        assertThat(cancelled.customerId()).isEqualTo(BUYER);
        assertThat(cancelled.type()).isEqualTo("ORDER_CANCELLED");
        assertThat(cancelled.body()).contains("MSS-20261108-501").contains("Quá hạn đặt cọc 48 giờ");
        assertThat(cancelled.target()).isEqualTo(buyerOrder());
    }

    @Test
    void disputeIsOpenedToTheSupplierDecidedToBothSidesAndRefundConfirmedToTheBuyer() {
        notifier.disputeOpened(SELLER_ORDER, 3, "Bánh bị vỡ");
        notifier.disputeDecided(SELLER_ORDER, 3, "REFUND", 4000L, "Hoàn hai bánh");
        notifier.refundConfirmed(SELLER_ORDER, 8, 4000);

        var opened = sent("DISPUTE_OPENED:3");
        assertThat(opened.supplierId()).isEqualTo(SUPPLIER);
        assertThat(opened.body()).contains("Bánh bị vỡ");
        assertThat(opened.target()).isEqualTo(sellerOrder());
        var toBuyer = sent("DISPUTE_RESOLVED:3:CUSTOMER");
        assertThat(toBuyer.customerId()).isEqualTo(BUYER);
        assertThat(toBuyer.body()).contains("4.000 ₫").contains("Hoàn hai bánh");
        var toSupplier = sent("DISPUTE_RESOLVED:3:SUPPLIER");
        assertThat(toSupplier.supplierId()).isEqualTo(SUPPLIER);
        assertThat(toSupplier.type()).isEqualTo("DISPUTE_RESOLVED");
        var refund = sent("REFUND_CONFIRMED:8");
        assertThat(refund.customerId()).isEqualTo(BUYER);
        assertThat(refund.body()).contains("4.000 ₫");
    }

    @Test
    void rejectedDisputeIsReportedToBothSides() {
        notifier.disputeDecided(SELLER_ORDER, 3, "REJECT", null, "Không có lỗi sản phẩm");

        assertThat(sent("DISPUTE_RESOLVED:3:CUSTOMER").body()).contains("Không có lỗi sản phẩm");
        assertThat(sent("DISPUTE_RESOLVED:3:SUPPLIER").body()).doesNotContain("₫");
    }

    @Test
    void confirmedPayoutNotifiesTheSupplier() {
        notifier.payoutPaid(SELLER_ORDER, 12, 9501, "bank-settlement-1");

        var paid = sent("PAYOUT_PAID:12");
        assertThat(paid.supplierId()).isEqualTo(SUPPLIER);
        assertThat(paid.type()).isEqualTo("PAYOUT_PAID");
        assertThat(paid.body()).contains("9.501 ₫").contains("bank-settlement-1").contains("MSS-20261108-501-601");
        assertThat(paid.target()).isEqualTo(sellerOrder());
    }

    @Test
    void longFreeTextIsCutToIdentityLimits() {
        notifier.disputeOpened(SELLER_ORDER, 3, "x".repeat(5000));

        assertThat(sent("DISPUTE_OPENED:3").body()).hasSize(1000);
    }

    @Test
    void identityFailuresAreSwallowed() {
        var request = Request.create(Request.HttpMethod.POST, "http://identity/internal", Map.of(), null,
                StandardCharsets.UTF_8, null);
        doThrow(new FeignException.ServiceUnavailable("down", request, null, null))
                .when(identity).createNotification(any());

        assertThatCode(() -> notifier.paymentCollected(SELLER_ORDER, 41, "FULL", 10001)).doesNotThrowAnyException();
        doThrow(new IllegalStateException("boom")).when(identity).createNotification(any());
        assertThatCode(() -> notifier.orderAccepted(SELLER_ORDER, false)).doesNotThrowAnyException();
    }

    @Test
    void anUnresolvableOrderIsSkippedWithoutFailingTheCaller() {
        when(orders.context(999)).thenThrow(new FinanceException(HttpStatus.CONFLICT, "PAYMENT_CONTEXT_NOT_READY"));

        assertThatCode(() -> notifier.shipmentShipped(999, 9, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> CommerceNotifier.disabled().orderAccepted(SELLER_ORDER, false)).doesNotThrowAnyException();
        verifyNoInteractions(identity);
    }

    @Test
    void insideATransactionNothingIsSentBeforeCommitAndNothingAfterRollback() {
        TransactionSynchronizationManager.initSynchronization();
        notifier.paymentCollected(SELLER_ORDER, 41, "DEPOSIT", 3000);
        verifyNoInteractions(identity);

        List<TransactionSynchronization> pending = TransactionSynchronizationManager.getSynchronizations();
        pending.forEach(TransactionSynchronization::afterCommit);
        verify(identity, times(2)).createNotification(any());

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        notifier.orderAccepted(SELLER_ORDER, false);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(identity, times(2)).createNotification(any());
    }
}
