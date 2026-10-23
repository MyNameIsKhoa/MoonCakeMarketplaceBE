package mss.mooncake.commerce.dto.request;

import java.time.LocalDateTime;

public record UpdateShipmentRequest(
        String shippingMethod,
        String provider,
        String trackingCode,
        LocalDateTime expectedDeliveryAt
) {}
