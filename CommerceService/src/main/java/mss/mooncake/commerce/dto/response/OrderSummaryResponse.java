package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderSummaryResponse(Long orderId, String orderNo, String sourceType, Long rfqIdRef,
                                   String status, LocalDateTime placedAt, BigDecimal subtotal,
                                   BigDecimal discountTotal, BigDecimal shippingTotal,
                                   BigDecimal grandTotal) { }
