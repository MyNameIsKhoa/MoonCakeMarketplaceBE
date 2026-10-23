package mss.mooncake.commerce.client;

import mss.mooncake.commerce.client.dto.catalog.ProductSnapshotResponse;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotResponse;
import mss.mooncake.commerce.client.dto.catalog.StockReservationRequest;
import mss.mooncake.commerce.client.dto.catalog.StockReservationResponse;
import mss.mooncake.commerce.client.dto.catalog.PromotionCalculationRequest;
import mss.mooncake.commerce.client.dto.catalog.PromotionCalculationResult;
import mss.mooncake.commerce.client.dto.catalog.StoreSummaryResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "catalog-store-service", url = "${services.catalog.base-url:http://localhost:8082}", configuration = CatalogFeignConfig.class)
public interface CatalogServiceClient {
    @GetMapping("/internal/v1/products/{productId}/variants/{variantId}/snapshot")
    ProductSnapshotResponse getProductSnapshot(@PathVariable Long productId, @PathVariable Long variantId);

    @PostMapping("/internal/v1/variants/snapshots")
    List<VariantSnapshotResponse> getVariantSnapshots(@RequestBody VariantSnapshotBatchRequest request);

    @PostMapping("/internal/v1/inventory/reservations")
    StockReservationResponse reserveStock(@RequestBody StockReservationRequest request);

    @GetMapping("/internal/v1/inventory/reservations/{reservationKey}")
    StockReservationResponse getStockReservation(@PathVariable String reservationKey);

    @PutMapping("/internal/v1/inventory/reservations/{reservationKey}/confirm")
    StockReservationResponse confirmStockReservation(@PathVariable String reservationKey);

    @PutMapping("/internal/v1/inventory/reservations/{reservationKey}/release")
    StockReservationResponse releaseStockReservation(@PathVariable String reservationKey);

    @PostMapping("/internal/v1/promotions/calculate")
    PromotionCalculationResult calculatePromotions(@RequestBody PromotionCalculationRequest request);

    @GetMapping("/internal/v1/stores/{storeId}/summary")
    StoreSummaryResponse getStoreSummary(@PathVariable Long storeId);
}
