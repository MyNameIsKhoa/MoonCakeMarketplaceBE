package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import mss.mooncake.commerce.dto.request.AddCartItemRequest;
import mss.mooncake.commerce.dto.request.UpdateCartItemRequest;
import mss.mooncake.commerce.dto.response.CartItemResponse;
import mss.mooncake.commerce.dto.response.CartResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.CartApplicationService;
import mss.mooncake.commerce.dto.internal.CartItemChange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartApplicationService carts;

    public CartController(CartApplicationService carts) { this.carts = carts; }

    @GetMapping
    public CartResponse get(@AuthenticationPrincipal CommercePrincipal principal) {
        return carts.getCart(customerId(principal));
    }

    @PostMapping("/pricing")
    public CartPricingResponse price(@AuthenticationPrincipal CommercePrincipal principal) {
        return carts.priceCart(customerId(principal));
    }

    @PostMapping("/items")
    public ResponseEntity<CartItemResponse> add(@AuthenticationPrincipal CommercePrincipal principal,
                                                 @Valid @RequestBody AddCartItemRequest request) {
        CartItemChange change = carts.addItem(customerId(principal), request);
        return ResponseEntity.status(change.created() ? HttpStatus.CREATED : HttpStatus.OK).body(change.item());
    }

    @PatchMapping("/items/{cartItemId}")
    public CartItemResponse update(@AuthenticationPrincipal CommercePrincipal principal,
                                   @PathVariable Long cartItemId,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return carts.updateItem(customerId(principal), cartItemId, request.quantity());
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal CommercePrincipal principal,
                                       @PathVariable Long cartItemId) {
        carts.removeItem(customerId(principal), cartItemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/items")
    public ResponseEntity<Void> clear(@AuthenticationPrincipal CommercePrincipal principal) {
        carts.clear(customerId(principal));
        return ResponseEntity.noContent().build();
    }

    private Long customerId(CommercePrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (principal.customerId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return principal.customerId();
    }
}
