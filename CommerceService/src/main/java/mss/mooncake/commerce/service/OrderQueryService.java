package mss.mooncake.commerce.service;

import mss.mooncake.commerce.dto.response.OrderDetailResponse;
import mss.mooncake.commerce.dto.response.OrderSummaryResponse;
import mss.mooncake.commerce.dto.response.SellerOrderResponse;
import mss.mooncake.commerce.dto.response.SellerOrderSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderQueryService {
    Page<OrderSummaryResponse> listCustomerOrders(Long customerId, Pageable pageable);
    OrderDetailResponse getCustomerOrder(Long customerId, Long orderId);
    Page<SellerOrderSummaryResponse> listSupplierOrders(Long userId, Pageable pageable);
    /** The buyer of the parent order, the owning supplier, or ({@code admin}) any administrator may read it. */
    SellerOrderResponse getSellerOrder(Long userId, Long customerId, Long sellerOrderId, boolean admin);
}
