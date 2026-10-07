package mss.mooncake.commerce.controller;

import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import mss.mooncake.commerce.service.PaymentApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/seller-orders")
public class CommerceContractController {
    private final PaymentApplicationService paymentApplicationService;
    public CommerceContractController(PaymentApplicationService paymentApplicationService) {
        this.paymentApplicationService = paymentApplicationService;
    }
    @GetMapping("/{sellerOrderId}/payment-context")
    public ResponseEntity<SellerOrderPaymentContext> getPaymentContext(@PathVariable Long sellerOrderId) {
        return ResponseEntity.ok(paymentApplicationService.getPaymentContext(sellerOrderId));
    }
}
