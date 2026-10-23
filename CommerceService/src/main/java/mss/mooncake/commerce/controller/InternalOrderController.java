package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Hidden;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
import mss.mooncake.commerce.service.OrderApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Hidden
@RequestMapping("/internal/v1/orders")
public class InternalOrderController {
    private final OrderApplicationService orderApplicationService;
    public InternalOrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }
    @PostMapping("/from-accepted-quotation")
    public ResponseEntity<OrderResponse> createFromAcceptedQuotation(
            @Valid @RequestBody CreateOrderFromQuotationRequest request) {
        return ResponseEntity.ok(orderApplicationService.createOrderFromAcceptedQuotation(request));
    }
}
