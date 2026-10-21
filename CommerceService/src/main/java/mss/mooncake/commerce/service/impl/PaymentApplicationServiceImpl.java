package mss.mooncake.commerce.service.impl;

import mss.mooncake.commerce.service.PaymentApplicationService;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import mss.mooncake.commerce.service.OrderApplicationService;
import org.springframework.stereotype.Service;

@Service
public class PaymentApplicationServiceImpl implements PaymentApplicationService {
    private final OrderApplicationService orders;

    public PaymentApplicationServiceImpl(OrderApplicationService orders) { this.orders = orders; }

    @Override
    public SellerOrderPaymentContext getPaymentContext(Long sellerOrderId) {
        return orders.getSellerOrderPaymentContext(sellerOrderId);
    }
}
