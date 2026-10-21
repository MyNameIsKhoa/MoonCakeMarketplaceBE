package mss.mooncake.commerce.client.dto.catalog;

import java.time.OffsetDateTime;
import java.util.List;

public record StockReservationRequest(String reservationKey, OffsetDateTime expiresAt, List<Line> lines) {
    public record Line(Long variantId, Integer quantity) { }
}
