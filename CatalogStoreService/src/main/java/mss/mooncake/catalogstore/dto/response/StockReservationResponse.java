package mss.mooncake.catalogstore.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

public record StockReservationResponse(String reservationKey, String status, OffsetDateTime expiresAt,
                                       List<Line> lines) {
    public record Line(Long variantId, Integer quantity) { }
}
