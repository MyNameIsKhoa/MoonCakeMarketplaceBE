package mss.mooncake.catalogstore.dto.response;

import java.util.List;

/**
 * {@code lines} are the units this restore key returned to stock. {@code reservationStatus} CANCELLED means the whole
 * reservation had already been returned (cancel-confirmed), so the request changed nothing.
 */
public record StockRestoreResponse(String restoreKey, String reservationKey, String reservationStatus,
                                   List<StockReservationResponse.Line> lines) { }
