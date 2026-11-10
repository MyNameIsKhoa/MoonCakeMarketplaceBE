package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import mss.mooncake.commerce.dto.request.CheckoutRequest;
import mss.mooncake.commerce.dto.request.CheckoutSelectionRequest;
import mss.mooncake.commerce.dto.response.RetailCheckoutPreview;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.RetailCheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {
    private final RetailCheckoutService checkout;
    public CheckoutController(RetailCheckoutService checkout) { this.checkout = checkout; }

    @PostMapping("/preview")
    public RetailCheckoutPreview preview(@AuthenticationPrincipal CommercePrincipal principal,
                                         @Valid @RequestBody CheckoutSelectionRequest selection) {
        requireCustomer(principal);
        return checkout.preview(principal.userId(), principal.customerId(), selection);
    }

    @PostMapping
    public ResponseEntity<RetailCheckoutResponse> place(@AuthenticationPrincipal CommercePrincipal principal,
                                        @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody CheckoutRequest request) {
        requireCustomer(principal);
        var order = checkout.place(principal.userId(), principal.customerId(), request.addressId(), key,
                request.selection(), request.quote());
        return ResponseEntity.created(URI.create("/api/orders/" + order.orderId())).body(order);
    }

    private void requireCustomer(CommercePrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (principal.customerId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
