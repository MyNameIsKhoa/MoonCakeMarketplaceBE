package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import mss.mooncake.commerce.enums.ShipmentStatus;

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
        ShipmentStatus status
) {}
