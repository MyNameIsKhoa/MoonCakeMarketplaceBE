package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.request.StockReservationRequest;
import mss.mooncake.catalogstore.dto.response.StockReservationResponse;

public interface StockReservationService {
    StockReservationResponse reserve(StockReservationRequest request);
    StockReservationResponse get(String reservationKey);
    StockReservationResponse confirm(String reservationKey);
    StockReservationResponse release(String reservationKey);
    StockReservationResponse cancelConfirmed(String reservationKey);
}
