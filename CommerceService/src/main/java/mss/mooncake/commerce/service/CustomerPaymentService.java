package mss.mooncake.commerce.service;

import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.dto.response.CustomerPaymentResponse;

/** One payOS checkout for all seller obligations in a retail customer order. */
public interface CustomerPaymentService {
    CustomerPaymentResponse summary(long orderId, FinanceService.Actor actor);
    CustomerPaymentResponse initiate(long orderId, String key, FinanceService.Actor actor);
    CustomerPaymentResponse reconcile(long orderId, FinanceService.Actor actor, boolean cancel);
    boolean receive(PaymentProvider.Receipt receipt);
    void cancelUnpaidOrder(long orderId, String reason, FinanceService.Actor actor);
}
