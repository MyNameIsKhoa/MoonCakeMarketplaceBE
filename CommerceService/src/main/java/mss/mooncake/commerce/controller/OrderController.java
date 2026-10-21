package mss.mooncake.commerce.controller;

import mss.mooncake.commerce.dto.response.OrderDetailResponse;
import mss.mooncake.commerce.dto.response.OrderSummaryResponse;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.OrderQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderQueryService queries;

    public OrderController(OrderQueryService queries) { this.queries = queries; }

    @GetMapping
    public Page<OrderSummaryResponse> list(@AuthenticationPrincipal CommercePrincipal principal,
                                           @PageableDefault(size = 20, sort = "placedAt",
                                                   direction = Sort.Direction.DESC) Pageable pageable) {
        return queries.listCustomerOrders(customerId(principal), pageable);
    }

    @GetMapping("/{orderId}")
    public OrderDetailResponse get(@AuthenticationPrincipal CommercePrincipal principal,
                                   @PathVariable Long orderId) {
        return queries.getCustomerOrder(customerId(principal), orderId);
    }

    private Long customerId(CommercePrincipal principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (principal.customerId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return principal.customerId();
    }
}
