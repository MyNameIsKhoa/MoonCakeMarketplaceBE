package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;

public interface RetailCheckoutService {
    /** {@code userId} is the buyer's account: a supplier may not buy from its own stores. */
    RetailCheckoutResponse place(long userId, long customerId, long addressId, String key);
    void recoverPending();
}
