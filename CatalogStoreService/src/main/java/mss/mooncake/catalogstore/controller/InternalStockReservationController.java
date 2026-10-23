package mss.mooncake.catalogstore.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import mss.mooncake.catalogstore.dto.request.StockReservationRequest;
import mss.mooncake.catalogstore.dto.response.StockReservationResponse;
import mss.mooncake.catalogstore.service.StockReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/inventory/reservations")
@Hidden
public class InternalStockReservationController {
    private final StockReservationService service;

    public InternalStockReservationController(StockReservationService service) { this.service = service; }

    @PostMapping
    public StockReservationResponse reserve(@Valid @RequestBody StockReservationRequest request) {
        return service.reserve(request);
    }

    @GetMapping("/{reservationKey}")
    public StockReservationResponse get(@PathVariable String reservationKey) { return service.get(reservationKey); }

    @PutMapping("/{reservationKey}/confirm")
    public StockReservationResponse confirm(@PathVariable String reservationKey) {
        return service.confirm(reservationKey);
    }

    @PutMapping("/{reservationKey}/release")
    public StockReservationResponse release(@PathVariable String reservationKey) {
        return service.release(reservationKey);
    }
}
