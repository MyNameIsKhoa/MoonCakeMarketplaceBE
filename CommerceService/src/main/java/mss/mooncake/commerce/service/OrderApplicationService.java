package mss.mooncake.commerce.service;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;

public interface OrderApplicationService {
    OrderResponse createOrderFromAcceptedQuotation(CreateOrderFromQuotationRequest request);
    OrderResponse getOrderByQuotation(Long quotationId);
    SellerOrderPaymentContext getSellerOrderPaymentContext(Long sellerOrderId);
    // TODO: Add cart checkout, multi-store split, and immutable order snapshot use
    // cases.
}
