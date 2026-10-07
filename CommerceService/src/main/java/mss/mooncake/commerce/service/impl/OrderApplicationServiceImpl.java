package mss.mooncake.commerce.service.impl;
import mss.mooncake.commerce.service.OrderApplicationService;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
import org.springframework.stereotype.Service;
@Service
public class OrderApplicationServiceImpl implements OrderApplicationService {
    @Override public OrderResponse createOrderFromAcceptedQuotation(CreateOrderFromQuotationRequest request) {
        throw new UnsupportedOperationException("TODO: implement idempotent quotation-to-order conversion");
    }
}
