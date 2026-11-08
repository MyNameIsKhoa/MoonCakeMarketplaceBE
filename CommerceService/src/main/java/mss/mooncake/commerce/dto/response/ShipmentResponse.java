package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.commerce.enums.ShipmentStatus;

/** {@code lines} is empty for a legacy shipment created before shipment lines existed (whole order). */
public record ShipmentResponse(
        Long shipmentId,
        Long sellerOrderId,
        String shippingMethod,
        String provider,
        String trackingCode,
        LocalDateTime expectedShipAt,
        LocalDateTime shippedAt,
        LocalDateTime expectedDeliveryAt,
        LocalDateTime deliveredAt,
        BigDecimal shippingFee,
        ShipmentStatus status,
        List<Line> lines
) {
    public record Line(Long orderItemId, String productNameSnapshot, String variantNameSnapshot, String skuSnapshot,
            Integer quantity) {}
}
