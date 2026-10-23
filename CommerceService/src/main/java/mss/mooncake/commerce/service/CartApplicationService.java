package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.request.AddCartItemRequest;
import mss.mooncake.commerce.dto.response.CartItemResponse;
import mss.mooncake.commerce.dto.response.CartResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;

public interface CartApplicationService {
    CartResponse getCart(Long customerId);
    CartPricingResponse priceCart(Long customerId);
    CartItemChange addItem(Long customerId, AddCartItemRequest request);
    CartItemResponse updateItem(Long customerId, Long itemId, int quantity);
    void removeItem(Long customerId, Long itemId);
    void clear(Long customerId);
}
