package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;

public interface RetailCheckoutService {
    RetailCheckoutResponse place(long customerId, long addressId, String key);
    void recoverPending();
}
