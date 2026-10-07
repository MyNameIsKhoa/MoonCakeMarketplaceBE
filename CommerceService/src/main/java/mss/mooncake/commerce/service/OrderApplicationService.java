package mss.mooncake.commerce.service;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
public interface OrderApplicationService {
    OrderResponse createOrderFromAcceptedQuotation(CreateOrderFromQuotationRequest request);
    // TODO: Add cart checkout, multi-store split, and immutable order snapshot use cases.
}
