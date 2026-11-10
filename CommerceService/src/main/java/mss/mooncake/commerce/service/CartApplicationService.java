package mss.mooncake.commerce.service;

import java.util.List;
import mss.mooncake.commerce.dto.request.AddCartItemRequest;
import mss.mooncake.commerce.dto.response.CartItemResponse;
import mss.mooncake.commerce.dto.internal.CartItemChange;
import mss.mooncake.commerce.dto.response.CartResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;

public interface CartApplicationService {
    CartResponse getCart(Long customerId);
    CartPricingResponse priceCart(List<CartItemResponse> lines, boolean checkAvailability);
    void select(Long customerId, List<Long> itemIds);
    CartItemChange addItem(Long customerId, AddCartItemRequest request);
    CartItemResponse updateItem(Long customerId, Long itemId, int quantity);
    void removeItem(Long customerId, Long itemId);
    void clear(Long customerId);
}
