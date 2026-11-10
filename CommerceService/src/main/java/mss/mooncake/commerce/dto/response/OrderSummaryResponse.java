package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderSummaryResponse(Long orderId, String orderNo, String sourceType, Long rfqIdRef,
                                   String status, LocalDateTime placedAt, BigDecimal subtotal,
                                   BigDecimal discountTotal, BigDecimal shippingTotal,
                                   BigDecimal grandTotal, int sellerOrderCount, int itemCount,
                                   List<String> sellerOrderStatuses, boolean hasCancellation) { }
