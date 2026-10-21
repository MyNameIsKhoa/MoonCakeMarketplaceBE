package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SellerOrderResponse(Long sellerOrderId, Long orderId, String sellerOrderNo,
                                  Long storeIdRef, Long supplierIdRef, Long quotationIdRef,
                                  String storeNameSnapshot, String supplierNameSnapshot,
                                  String status, BigDecimal subtotal, BigDecimal shippingFee,
                                  BigDecimal totalAmount, BigDecimal depositPercent,
                                  BigDecimal depositAmount, BigDecimal remainingAmount,
                                  LocalDateTime remainingDueAt, LocalDate deliveryDateSnapshot,
                                  String shippingMethodSnapshot, String paymentTermsSnapshot,
                                  List<OrderItemResponse> items) { }
