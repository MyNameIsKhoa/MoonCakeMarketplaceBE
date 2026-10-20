package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;

public interface PaymentApplicationService {
    SellerOrderPaymentContext getPaymentContext(Long sellerOrderId);
    // TODO: Deposit/full/remaining obligations, provider callback idempotency, and
    // HELD/RELEASED transitions.
}
