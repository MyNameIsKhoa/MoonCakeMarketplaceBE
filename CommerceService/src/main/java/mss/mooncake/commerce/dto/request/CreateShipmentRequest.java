package mss.mooncake.commerce.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CreateShipmentRequest(
        @NotNull(message = "sellerOrderId is required")
        Long sellerOrderId,

        @NotBlank(message = "shippingMethod is required")
        String shippingMethod,

        String provider,

        String trackingCode,

        LocalDateTime expectedShipAt,

        LocalDateTime expectedDeliveryAt,

        @NotNull(message = "shippingFee is required")
        @DecimalMin(value = "0.0", inclusive = true, message = "shippingFee must be non-negative")
        BigDecimal shippingFee
) {}
