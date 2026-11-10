package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.request.CheckoutSelectionRequest;
import mss.mooncake.commerce.dto.response.RetailCheckoutPreview;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;

public interface RetailCheckoutService {
    /** {@code userId} is the buyer's account: a supplier may not buy from its own stores. */
    RetailCheckoutPreview preview(long userId, long customerId, CheckoutSelectionRequest selection);
    RetailCheckoutResponse place(long userId, long customerId, long addressId, String key,
                                 CheckoutSelectionRequest selection, String quote);
    void recoverPending();
}
