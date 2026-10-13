package mss.mooncake.rfqnegotiation.client;
import mss.mooncake.rfqnegotiation.dto.request.CreateOrderFromAcceptedQuotationRequest;
import mss.mooncake.rfqnegotiation.client.dto.commerce.OrderResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
@FeignClient(name = "commerce-service", url = "${services.commerce.base-url:http://localhost:8083}", configuration = CommerceFeignConfig.class)
public interface CommerceServiceClient {
    @PostMapping("/internal/v1/orders/from-accepted-quotation")
    OrderResponse createOrderFromAcceptedQuotation(@RequestBody CreateOrderFromAcceptedQuotationRequest request);
}
