package mss.mooncake.commerce.controller;

import mss.mooncake.commerce.dto.response.SellerOrderResponse;
import mss.mooncake.commerce.dto.response.SellerOrderSummaryResponse;
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
@RequestMapping("/api/seller-orders")
public class SellerOrderController {
    private final OrderQueryService queries;

    public SellerOrderController(OrderQueryService queries) { this.queries = queries; }

    @GetMapping
    public Page<SellerOrderSummaryResponse> list(@AuthenticationPrincipal CommercePrincipal principal,
                                                  @PageableDefault(size = 20, sort = "id",
                                                          direction = Sort.Direction.DESC) Pageable pageable) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return queries.listSupplierOrders(principal.userId(), pageable);
    }

    @GetMapping("/{sellerOrderId}")
    public SellerOrderResponse get(@AuthenticationPrincipal CommercePrincipal principal,
                                   @PathVariable Long sellerOrderId) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return queries.getSellerOrder(principal.userId(), principal.customerId(), sellerOrderId);
    }
}
