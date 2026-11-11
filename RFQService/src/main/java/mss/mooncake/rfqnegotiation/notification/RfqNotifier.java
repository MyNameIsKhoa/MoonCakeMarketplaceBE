package mss.mooncake.rfqnegotiation.notification;

import feign.FeignException;
import java.util.Collection;
import java.util.Map;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.rfqnegotiation.entity.ConversationEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationConversionEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-app notifications for RFQ events, stored by Identity (POST /internal/v1/notifications).
 * Inside a transaction a notification is sent only after commit, so a rolled-back action notifies nobody.
 * Delivery is best effort: any failure is logged and never reaches the business action.
 * Targets marked role=SELLER open the seller center on the FE; the others open the buyer's pages.
 */
@Component
public class RfqNotifier {
    private static final Logger log = LoggerFactory.getLogger(RfqNotifier.class);
    private static final int MAX_TITLE = 255;
    private static final int MAX_BODY = 1000;
    private static final int CHAT_PREVIEW = 200;
    private static final String SELLER = "SELLER";

    private final IdentityServiceClient identity;

    public RfqNotifier(IdentityServiceClient identity) {
        this.identity = identity;
    }

    /** A private RFQ was sent to these suppliers' stores. */
    public void rfqInvited(RfqEntity rfq, Collection<Long> supplierIds) {
        for (Long supplierId : supplierIds) {
            send(new CreateNotificationRequest(null, null, supplierId, "RFQ_INVITED", "Lời mời báo giá mới",
                    "Bạn được mời báo giá cho yêu cầu " + quoted(rfq) + " (" + rfq.getQuantity() + " sản phẩm).",
                    Map.of("kind", "RFQ", "id", id(rfq.getId()), "role", SELLER), "RFQ_INVITED:" + rfq.getId()));
        }
    }

    /** A new quotation, or a new version of one, reached the buyer. */
    public void quotationSubmitted(RfqEntity rfq, QuotationEntity quotation, String storeName) {
        boolean revision = quotation.getPreviousQuotationId() != null;
        String type = revision ? "QUOTATION_REVISED" : "QUOTATION_RECEIVED";
        String store = storeName == null || storeName.isBlank() ? "Nhà cung cấp" : storeName.strip();
        String body = revision
                ? store + " đã gửi phiên bản " + quotation.getVersionNo() + " của báo giá cho yêu cầu " + quoted(rfq) + "."
                : store + " đã gửi báo giá cho yêu cầu " + quoted(rfq) + ".";
        send(new CreateNotificationRequest(null, rfq.getCustomerIdRef(), null, type,
                revision ? "Báo giá đã được cập nhật" : "Báo giá mới cho yêu cầu của bạn", body,
                Map.of("kind", "QUOTATION", "rfqId", id(rfq.getId()), "quotationId", id(quotation.getId())),
                type + ":" + quotation.getId()));
    }

    public void quotationRejected(RfqEntity rfq, QuotationEntity quotation) {
        String reason = quotation.getDecisionReason();
        send(toSupplier(quotation, rfq, "QUOTATION_REJECTED", "Báo giá bị từ chối",
                "Người mua đã từ chối báo giá phiên bản " + quotation.getVersionNo() + " cho yêu cầu " + quoted(rfq) + "."
                        + (reason == null || reason.isBlank() ? "" : " Lý do: " + reason.strip()),
                "QUOTATION_REJECTED:" + quotation.getId()));
    }

    /** Declined because the buyer accepted another quotation. No dedup key: it may compete again if that order is cancelled. */
    public void quotationNotSelected(RfqEntity rfq, QuotationEntity quotation) {
        send(toSupplier(quotation, rfq, "QUOTATION_NOT_SELECTED", "Báo giá không được chọn",
                "Người mua đã chốt báo giá khác cho yêu cầu " + quoted(rfq) + " nên báo giá của bạn không được chọn.", null));
    }

    public void rfqCancelled(RfqEntity rfq, QuotationEntity quotation) {
        send(toSupplier(quotation, rfq, "RFQ_CANCELLED", "Yêu cầu báo giá đã bị hủy",
                "Người mua đã hủy yêu cầu " + quoted(rfq) + "; báo giá đang chờ của bạn đã được đóng.",
                "RFQ_CANCELLED:" + quotation.getId()));
    }

    /** The buyer accepted and Commerce created the order. */
    public void quotationAccepted(RfqEntity rfq, QuotationEntity quotation, QuotationConversionEntity conversion) {
        send(new CreateNotificationRequest(null, null, quotation.getSupplierIdRef(), "QUOTATION_ACCEPTED",
                "Báo giá được chấp nhận",
                "Người mua đã chấp nhận báo giá cho yêu cầu " + quoted(rfq) + ". Đơn hàng đã được tạo.",
                Map.of("kind", "ORDER", "id", id(conversion.getSellerOrderIdRef()), "role", SELLER),
                "QUOTATION_ACCEPTED:" + quotation.getId()));
    }

    /**
     * The order sync found the order cancelled. Keys are the order-level ones, so a Commerce notification
     * for the same cancellation with the same key is not shown twice.
     */
    public void orderCancelled(RfqEntity rfq, QuotationEntity quotation, QuotationConversionEntity conversion) {
        send(new CreateNotificationRequest(null, rfq.getCustomerIdRef(), null, "ORDER_CANCELLED", "Đơn hàng đã bị hủy",
                "Đơn hàng tạo từ báo giá cho yêu cầu " + quoted(rfq) + " đã bị hủy.",
                Map.of("kind", "ORDER", "id", id(conversion.getOrderIdRef())),
                "ORDER_CANCELLED:" + conversion.getOrderIdRef()));
        send(new CreateNotificationRequest(null, null, quotation.getSupplierIdRef(), "ORDER_CANCELLED", "Đơn hàng đã bị hủy",
                "Đơn hàng từ báo giá của bạn cho yêu cầu " + quoted(rfq) + " đã bị hủy.",
                Map.of("kind", "ORDER", "id", id(conversion.getSellerOrderIdRef()), "role", SELLER),
                "SELLER_ORDER_CANCELLED:" + conversion.getSellerOrderIdRef()));
    }

    /**
     * New chat message for the other participant. The key changes only when the recipient reads the
     * conversation, so a burst of messages keeps one notification per conversation until then.
     */
    public void chatMessage(ConversationEntity conversation, boolean senderIsBuyer, String senderName, String content,
                            Long recipientLastReadMessageId) {
        String title = senderName == null || senderName.isBlank() ? "Tin nhắn mới" : "Tin nhắn mới từ " + senderName.strip();
        send(new CreateNotificationRequest(null,
                senderIsBuyer ? null : conversation.getCustomerIdRef(),
                senderIsBuyer ? conversation.getSupplierIdRef() : null,
                "CHAT_MESSAGE", title, truncate(content == null ? "" : content.strip(), CHAT_PREVIEW),
                Map.of("kind", "CONVERSATION", "id", id(conversation.getId()), "rfqId", id(conversation.getRfqId())),
                "CHAT_MESSAGE:" + conversation.getId() + ":" + (recipientLastReadMessageId == null ? 0 : recipientLastReadMessageId)));
    }

    private static CreateNotificationRequest toSupplier(QuotationEntity quotation, RfqEntity rfq, String type, String title,
                                                        String body, String dedupKey) {
        return new CreateNotificationRequest(null, null, quotation.getSupplierIdRef(), type, title, body,
                Map.of("kind", "QUOTATION", "rfqId", id(rfq.getId()), "quotationId", id(quotation.getId()), "role", SELLER),
                dedupKey);
    }

    private void send(CreateNotificationRequest request) {
        CreateNotificationRequest bounded = new CreateNotificationRequest(request.userId(), request.customerId(),
                request.supplierId(), request.type(), truncate(request.title(), MAX_TITLE), truncate(request.body(), MAX_BODY),
                request.target(), request.dedupKey());
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

    private static String quoted(RfqEntity rfq) {
        return "\"" + rfq.getTitle() + "\"";
    }

    private static String id(Long value) {
        return String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }
}
