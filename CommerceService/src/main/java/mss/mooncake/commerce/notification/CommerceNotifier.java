package mss.mooncake.commerce.notification;

import feign.FeignException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPaymentItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-app notifications for order, payment and finance events, stored by Identity (POST /internal/v1/notifications).
 * Recipients and order numbers are read from Commerce's own order and finance data when the event happens.
 * Inside a transaction a notification is sent only after commit, so a rolled-back action notifies nobody.
 * Delivery is best effort and never retried: any failure (lookup or Identity call) is logged and never reaches
 * the business action. Buyer targets open the customer order; targets with role=SELLER open the seller order.
 * Cancellations use RFQService's keys (ORDER_CANCELLED:{orderId}, SELLER_ORDER_CANCELLED:{sellerOrderId}) so an
 * RFQ order cancelled here is not announced twice.
 */
@Component
public class CommerceNotifier {
    private static final Logger log = LoggerFactory.getLogger(CommerceNotifier.class);
    private static final int MAX_TITLE = 255;
    private static final int MAX_BODY = 1000;
    private static final String SELLER = "SELLER";

    private final IdentityServiceClient identity;
    private final FinanceRepository orders;

    public CommerceNotifier(IdentityServiceClient identity, FinanceRepository orders) {
        this.identity = identity;
        this.orders = orders;
    }

    /** Sends nothing: for services built directly in tests that do not check notifications. */
    public static CommerceNotifier disabled() {
        return new CommerceNotifier(null, null);
    }

    /** A seller-order payment link was collected (webhook or reconciliation). */
    public void paymentCollected(long sellerOrderId, long paymentId, String paymentType, long amount) {
        emit("PAYMENT_RECEIVED", () -> {
            Order order = order(sellerOrderId);
            String paid = switch (String.valueOf(paymentType)) {
                case "DEPOSIT" -> "Khoản đặt cọc ";
                case "REMAINING" -> "Khoản thanh toán phần còn lại ";
                default -> "Khoản thanh toán ";
            };
            return List.of(paymentReceived(order, paymentId, paymentType, amount),
                    toBuyer(order, "PAYMENT_CONFIRMED", "Thanh toán thành công",
                            paid + money(amount) + " cho đơn hàng " + order.number() + " đã được xác nhận.",
                            "PAYMENT_CONFIRMED:" + paymentId));
        });
    }

    /** One checkout payment covered every seller order of a retail order. */
    public void customerPaymentCollected(long customerPaymentId, long amount, List<CustomerPaymentItem> items) {
        emit("PAYMENT_CONFIRMED", () -> {
            List<CreateNotificationRequest> requests = new ArrayList<>();
            Order first = null;
            for (CustomerPaymentItem item : items) {
                Order order = order(item.sellerOrderId());
                if (first == null) first = order;
                requests.add(paymentReceived(order, item.paymentId(), "FULL", item.amount()));
            }
            if (first != null) {
                requests.add(toBuyer(first, "PAYMENT_CONFIRMED", "Thanh toán thành công",
                        "Khoản thanh toán " + money(amount) + " cho đơn hàng " + first.number() + " đã được xác nhận.",
                        "PAYMENT_CONFIRMED:CUSTOMER_PAYMENT:" + customerPaymentId));
            }
            return requests;
        });
    }

    public void shipmentShipped(long sellerOrderId, long shipmentId, String carrier, String trackingCode) {
        emit("SHIPMENT_SHIPPED", () -> {
            Order order = order(sellerOrderId);
            String via = (present(carrier) ? " qua " + carrier.strip() : "")
                    + (present(trackingCode) ? ", mã vận đơn " + trackingCode.strip() : "");
            return List.of(toBuyer(order, "SHIPMENT_SHIPPED", "Đơn hàng đang được giao",
                    "Một kiện hàng của đơn hàng " + order.number() + " đã được gửi đi" + via + ".",
                    "SHIPMENT_SHIPPED:" + shipmentId));
        });
    }

    /** A shipment was delivered; when it completed the seller order the buyer is also asked to accept it. */
    public void shipmentDelivered(long sellerOrderId, long shipmentId, boolean orderDelivered) {
        emit("SHIPMENT_DELIVERED", () -> {
            Order order = order(sellerOrderId);
            List<CreateNotificationRequest> requests = new ArrayList<>();
            requests.add(toBuyer(order, "SHIPMENT_DELIVERED", "Kiện hàng đã được giao",
                    "Một kiện hàng của đơn hàng " + order.number() + " đã được giao thành công.",
                    "SHIPMENT_DELIVERED:" + shipmentId));
            if (orderDelivered) {
                requests.add(toBuyer(order, "ORDER_DELIVERED", "Vui lòng nghiệm thu đơn hàng",
                        "Toàn bộ sản phẩm của đơn hàng " + order.number() + " (" + order.sellerNumber()
                                + ") đã được giao. Vui lòng kiểm tra và xác nhận nghiệm thu trong 7 ngày; "
                                + "sau thời hạn này, nếu không có khiếu nại, đơn hàng sẽ được tự động nghiệm thu.",
                        "ORDER_DELIVERED:" + sellerOrderId));
            }
            return requests;
        });
    }

    public void orderAccepted(long sellerOrderId, boolean automatic) {
        emit("ORDER_ACCEPTED", () -> {
            Order order = order(sellerOrderId);
            return List.of(toSupplier(order, "ORDER_ACCEPTED", "Đơn hàng đã được nghiệm thu", automatic
                            ? "Đơn hàng " + order.sellerNumber() + " đã được tự động nghiệm thu vì người mua không "
                                    + "xác nhận hay khiếu nại trong 7 ngày sau khi giao."
                            : "Người mua đã xác nhận nghiệm thu đơn hàng " + order.sellerNumber() + ".",
                    "ORDER_ACCEPTED:" + sellerOrderId));
        });
    }

    /** requester is the cancellation actor_type; a supplier is not told about its own request. */
    public void cancellationRequested(long sellerOrderId, long cancellationId, String requester, String reason) {
        if ("SUPPLIER".equals(requester)) return;
        emit("CANCELLATION_REQUESTED", () -> {
            Order order = order(sellerOrderId);
            String who = "ADMIN".equals(requester) ? "Quản trị viên" : "Người mua";
            return List.of(toSupplier(order, "CANCELLATION_REQUESTED", "Yêu cầu hủy đơn hàng",
                    who + " đã yêu cầu hủy đơn hàng " + order.sellerNumber() + "." + because(reason),
                    "CANCELLATION_REQUESTED:" + cancellationId));
        });
    }

    public void cancellationDecided(long sellerOrderId, long cancellationId, boolean approved, String reason) {
        emit(approved ? "ORDER_CANCELLED" : "CANCELLATION_REJECTED", () -> {
            Order order = order(sellerOrderId);
            if (!approved) {
                return List.of(toBuyer(order, "CANCELLATION_REJECTED", "Yêu cầu hủy đơn bị từ chối",
                        "Yêu cầu hủy đơn hàng " + order.number() + " đã bị từ chối." + because(reason),
                        "CANCELLATION_REJECTED:" + cancellationId));
            }
            // A multi-store checkout may lose one store at a time: one notice per store, never deduplicated away.
            boolean partOfCheckout = orders.sellerOrderIds(order.id()).size() > 1;
            return List.of(
                    toBuyer(order, "ORDER_CANCELLED", "Đơn hàng đã bị hủy", (partOfCheckout
                                    ? "Phần đơn hàng " + order.sellerNumber() + " trong đơn " + order.number()
                                    : "Đơn hàng " + order.number()) + " đã được hủy." + because(reason),
                            "ORDER_CANCELLED:" + order.id() + (partOfCheckout ? ":" + sellerOrderId : "")),
                    toSupplier(order, "ORDER_CANCELLED", "Đơn hàng đã bị hủy",
                            "Đơn hàng " + order.sellerNumber() + " đã được hủy." + because(reason),
                            "SELLER_ORDER_CANCELLED:" + sellerOrderId));
        });
    }

    /** The system cancelled an unpaid RFQ or retail order (payment or deposit timeout). */
    public void unpaidOrderCancelled(long orderId, String reason) {
        emit("ORDER_CANCELLED", () -> List.of(new CreateNotificationRequest(null, orders.customerOrderOwner(orderId),
                null, "ORDER_CANCELLED", "Đơn hàng đã bị hủy",
                "Đơn hàng " + number(orders.customerOrderNumber(orderId), orderId)
                        + " đã được hệ thống tự động hủy vì chưa được thanh toán." + because(reason),
                Map.of("kind", "ORDER", "id", String.valueOf(orderId)), "ORDER_CANCELLED:" + orderId)));
    }

    public void disputeOpened(long sellerOrderId, long disputeId, String reason) {
        emit("DISPUTE_OPENED", () -> {
            Order order = order(sellerOrderId);
            return List.of(toSupplier(order, "DISPUTE_OPENED", "Khiếu nại mới",
                    "Người mua đã mở khiếu nại cho đơn hàng " + order.sellerNumber() + "." + because(reason)
                            + " Vui lòng phản hồi trong 48 giờ.",
                    "DISPUTE_OPENED:" + disputeId));
        });
    }

    /** An administrator decided the dispute: REJECT closes it, REFUND awards the buyer an amount. */
    public void disputeDecided(long sellerOrderId, long disputeId, String decision, Long amount, String resolution) {
        emit("DISPUTE_RESOLVED", () -> {
            Order order = order(sellerOrderId);
            boolean refund = "REFUND".equals(decision) && amount != null;
            String note = present(resolution) ? " Ghi chú: " + sentence(resolution) : "";
            return List.of(
                    toBuyer(order, "DISPUTE_RESOLVED", "Khiếu nại đã có kết quả", (refund
                                    ? "Khiếu nại cho đơn hàng " + order.number() + " được chấp nhận: bạn sẽ được hoàn "
                                            + money(amount) + "."
                                    : "Khiếu nại cho đơn hàng " + order.number() + " không được chấp nhận.") + note,
                            "DISPUTE_RESOLVED:" + disputeId + ":CUSTOMER"),
                    toSupplier(order, "DISPUTE_RESOLVED", "Khiếu nại đã có kết quả", (refund
                                    ? "Khiếu nại cho đơn hàng " + order.sellerNumber() + " được chấp nhận: người mua được hoàn "
                                            + money(amount) + "."
                                    : "Khiếu nại cho đơn hàng " + order.sellerNumber() + " đã bị bác bỏ.") + note,
                            "DISPUTE_RESOLVED:" + disputeId + ":SUPPLIER"));
        });
    }

    public void refundConfirmed(long sellerOrderId, long refundId, long amount) {
        emit("REFUND_CONFIRMED", () -> {
            Order order = order(sellerOrderId);
            return List.of(toBuyer(order, "REFUND_CONFIRMED", "Hoàn tiền thành công",
                    "Khoản hoàn tiền " + money(amount) + " cho đơn hàng " + order.number() + " đã được chuyển.",
                    "REFUND_CONFIRMED:" + refundId));
        });
    }

    public void payoutPaid(long sellerOrderId, long settlementId, long amount, String reference) {
        emit("PAYOUT_PAID", () -> {
            Order order = order(sellerOrderId);
            return List.of(toSupplier(order, "PAYOUT_PAID", "Đã giải ngân",
                    "Khoản giải ngân " + money(amount) + " cho đơn hàng " + order.sellerNumber() + " đã được chuyển."
                            + (present(reference) ? " Mã giao dịch: " + sentence(reference) : ""),
                    "PAYOUT_PAID:" + settlementId));
        });
    }

    /** Parties and public numbers of one seller order and its customer order. */
    private record Order(long id, String number, long customerId, long sellerOrderId, String sellerNumber,
                         long supplierId) { }

    private Order order(long sellerOrderId) {
        var context = orders.context(sellerOrderId);
        long orderId = orders.parentOrderId(sellerOrderId);
        return new Order(orderId, number(orders.customerOrderNumber(orderId), orderId), context.customerId(),
                sellerOrderId, number(orders.sellerOrderNumber(sellerOrderId), sellerOrderId), context.supplierId());
    }

    private static CreateNotificationRequest paymentReceived(Order order, long paymentId, String type, long amount) {
        String title = switch (String.valueOf(type)) {
            case "DEPOSIT" -> "Đã nhận tiền đặt cọc";
            case "REMAINING" -> "Đã nhận thanh toán phần còn lại";
            default -> "Đã nhận thanh toán đủ";
        };
        return toSupplier(order, "PAYMENT_RECEIVED", title, "Người mua đã thanh toán " + money(amount)
                        + " cho đơn hàng " + order.sellerNumber() + ". Sàn giữ khoản tiền này đến khi quyết toán.",
                "PAYMENT_RECEIVED:" + paymentId);
    }

    private static CreateNotificationRequest toBuyer(Order order, String type, String title, String body, String key) {
        return new CreateNotificationRequest(null, order.customerId(), null, type, title, body,
                Map.of("kind", "ORDER", "id", String.valueOf(order.id())), key);
    }

    private static CreateNotificationRequest toSupplier(Order order, String type, String title, String body,
                                                        String key) {
        return new CreateNotificationRequest(null, null, order.supplierId(), type, title, body,
                Map.of("kind", "ORDER", "id", String.valueOf(order.sellerOrderId()), "role", SELLER), key);
    }

    /** Builds the requests now (inside the caller's transaction) and sends them after it commits. */
    private void emit(String event, Supplier<List<CreateNotificationRequest>> build) {
        if (identity == null) return;
        List<CreateNotificationRequest> requests;
        try {
            requests = build.get();
        } catch (RuntimeException failure) {
            log.warn("Notification {} skipped: recipients not resolved; failureType={}", event,
                    failure.getClass().getSimpleName());
            return;
        }
        requests.forEach(this::send);
    }

    private void send(CreateNotificationRequest request) {
        CreateNotificationRequest bounded = new CreateNotificationRequest(request.userId(), request.customerId(),
                request.supplierId(), request.type(), truncate(request.title(), MAX_TITLE),
                truncate(request.body(), MAX_BODY), request.target(), request.dedupKey());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(bounded);
                }
            });
        } else {
            deliver(bounded);
        }
    }

    private void deliver(CreateNotificationRequest request) {
        try {
            identity.createNotification(request);
        } catch (RuntimeException failure) {
            log.warn("Notification {} not delivered; failureType={} downstreamStatus={}", request.type(),
                    failure.getClass().getSimpleName(), failure instanceof FeignException feign ? feign.status() : null);
        }
    }

    private static String because(String reason) {
        return present(reason) ? " Lý do: " + sentence(reason) : "";
    }

    private static String sentence(String text) {
        String value = text.strip();
        return value.matches("(?s).*[.!?…]$") ? value : value + ".";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String number(String value, long id) {
        return present(value) ? value : "#" + id;
    }

    /** VND with dot grouping, e.g. 10.001 ₫. */
    private static String money(long amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', '.') + " ₫";
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }
}
