package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record RetailCheckoutResponse(Long orderId, String orderNo, String status,
                                     BigDecimal grandTotal, List<Seller> sellerOrders) {
    public record Seller(Long sellerOrderId, String sellerOrderNo, BigDecimal totalAmount) { }
}
