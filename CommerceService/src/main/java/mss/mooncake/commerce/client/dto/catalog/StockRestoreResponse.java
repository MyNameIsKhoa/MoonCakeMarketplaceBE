package mss.mooncake.commerce.client.dto.catalog;

import java.util.List;

/** {@code reservationStatus} CANCELLED: the whole reservation had already been returned, nothing changed. */
public record StockRestoreResponse(String restoreKey, String reservationKey, String reservationStatus,
                                   List<StockReservationResponse.Line> lines) { }
