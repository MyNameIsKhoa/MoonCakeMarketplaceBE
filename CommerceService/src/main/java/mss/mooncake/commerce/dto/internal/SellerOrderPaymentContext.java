package mss.mooncake.commerce.dto.internal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record SellerOrderPaymentContext(Long sellerOrderId, BigDecimal totalAmount,
                                        BigDecimal depositPercent, LocalDateTime remainingDueAt,
                                        String orderStatus) { }
