package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;

public record SellerOrderSummaryResponse(Long sellerOrderId, Long orderId, String sellerOrderNo,
                                         Long storeIdRef, String storeNameSnapshot, String status,
                                         BigDecimal totalAmount) { }
