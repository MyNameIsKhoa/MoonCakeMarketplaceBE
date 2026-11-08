package mss.mooncake.commerce.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code lines} chooses which order items (and how many units) travel in this shipment. Null or empty means
 * "every unit of the seller order not yet in a non-cancelled shipment" (the behaviour before lines existed).
 */
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
        BigDecimal shippingFee,

        @Size(max = 200, message = "A shipment holds at most 200 lines")
        List<@Valid @NotNull Line> lines
) {
    public record Line(
            @NotNull(message = "orderItemId is required") Long orderItemId,
            @NotNull(message = "quantity is required") @Positive(message = "quantity must be positive") Integer quantity) {}

    /** A shipment of every unit not yet shipped. */
    public CreateShipmentRequest(Long sellerOrderId, String shippingMethod, String provider, String trackingCode,
            LocalDateTime expectedShipAt, LocalDateTime expectedDeliveryAt, BigDecimal shippingFee) {
        this(sellerOrderId, shippingMethod, provider, trackingCode, expectedShipAt, expectedDeliveryAt, shippingFee, null);
    }
}
