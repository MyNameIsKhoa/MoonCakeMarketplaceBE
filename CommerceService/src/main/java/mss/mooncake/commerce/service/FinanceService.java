package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.response.FinanceResponse;
import vn.payos.model.webhooks.Webhook;

public interface FinanceService {
    record Actor(long userId, Long customerId, Long supplierId, boolean admin) { }

    FinanceResponse summary(long orderId, Actor actor);
    FinanceResponse.Payment initiate(long paymentId, String commandKey, Actor actor);
    void webhook(Webhook webhook);
    FinanceResponse.Payment reconcile(long paymentId, Actor actor, boolean cancel);
    void requestCancellation(long orderId, String reason, String commandKey, Actor actor);
    void decideCancellation(long orderId, long id, boolean approve, String reason, String commandKey, Actor actor);
    void openDispute(long orderId, String reason, String evidence, String commandKey, Actor actor);
    void respondDispute(long orderId, long id, String response, String commandKey, Actor actor);
    void escalateDispute(long orderId, long id, String commandKey, Actor actor);
    void decideDispute(long orderId, long id, String decision, Long amount, String reason,
            String commandKey, Actor actor);
    void addEvidence(long orderId, long id, String evidence, String commandKey, Actor actor);
    void rejectRefund(long orderId, long id, String reason, String commandKey, Actor actor);
    void requestRefund(long orderId, long paymentId, long amount, String reason, String policy,
            String commandKey, Actor actor);
    void requestRefund(long orderId, long paymentId, long amount, Long disputeId, String reason,
            String policy, String commandKey, Actor actor);
    void confirmRefund(long orderId, long id, String reference, String evidence, String commandKey, Actor actor);
    void createSettlement(long orderId, String commandKey, Actor actor);
    void beginPayout(long orderId, long id, String commandKey, Actor actor);
    void confirmPayout(long orderId, long id, String reference, String evidence, String commandKey, Actor actor);
    /** Settlement state of the calling supplier's seller orders (page) with pending/ready/paid totals. */
    FinanceResponse.SupplierPayouts supplierPayouts(Actor actor, int page, int size);
}
