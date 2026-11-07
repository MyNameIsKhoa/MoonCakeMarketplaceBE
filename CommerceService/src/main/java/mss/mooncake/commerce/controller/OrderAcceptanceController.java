package mss.mooncake.commerce.controller;

import mss.mooncake.commerce.dto.response.SellerOrderAcceptanceResponse;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seller-orders")
public class OrderAcceptanceController {

    private final FulfillmentApplicationService fulfillmentService;

    public OrderAcceptanceController(FulfillmentApplicationService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping("/{sellerOrderId}/accept")
    public ResponseEntity<SellerOrderAcceptanceResponse> acceptSellerOrder(
            @PathVariable Long sellerOrderId,
            @AuthenticationPrincipal CommercePrincipal principal) {
        Long customerId = principal != null && principal.customerId() != null
                ? principal.customerId()
                : null;
        return ResponseEntity.ok(fulfillmentService.acceptSellerOrder(sellerOrderId, customerId));
    }
}
