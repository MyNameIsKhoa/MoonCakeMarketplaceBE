package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.request.StockReservationRequest;
import mss.mooncake.catalogstore.dto.request.StockRestoreRequest;
import mss.mooncake.catalogstore.dto.response.StockReservationResponse;
import mss.mooncake.catalogstore.dto.response.StockRestoreResponse;

public interface StockReservationService {
    StockReservationResponse reserve(StockReservationRequest request);
    StockReservationResponse get(String reservationKey);
    StockReservationResponse confirm(String reservationKey);
    StockReservationResponse release(String reservationKey);
    StockReservationResponse cancelConfirmed(String reservationKey);
    /** Returns specific confirmed lines to stock, idempotently per restore key (partial cancellation). */
    StockRestoreResponse restoreLines(String reservationKey, StockRestoreRequest request);
}
