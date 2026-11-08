package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code discountTotal} is the sum of the item discounts; {@code deliveryAddress} is the order's address
 * snapshot (null only for a legacy order without one), so the supplier knows where to ship.
 */
public record SellerOrderResponse(Long sellerOrderId, Long orderId, String sellerOrderNo,
                                  Long storeIdRef, Long supplierIdRef, Long quotationIdRef,
                                  String storeNameSnapshot, String supplierNameSnapshot,
                                  String status, BigDecimal subtotal, BigDecimal shippingFee,
                                  BigDecimal totalAmount, BigDecimal depositPercent,
                                  BigDecimal depositAmount, BigDecimal remainingAmount,
                                  LocalDateTime remainingDueAt, LocalDate deliveryDateSnapshot,
                                  String shippingMethodSnapshot, String paymentTermsSnapshot,
                                  List<OrderItemResponse> items, BigDecimal discountTotal,
                                  DeliveryAddress deliveryAddress) {
    public record DeliveryAddress(String recipientName, String recipientPhone, String addressLine,
                                  String ward, String district, String city) { }
}
