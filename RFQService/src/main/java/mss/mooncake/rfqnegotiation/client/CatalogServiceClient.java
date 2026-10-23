package mss.mooncake.rfqnegotiation.client;
import mss.mooncake.rfqnegotiation.client.dto.catalog.ProductSnapshotResponse;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
@FeignClient(name = "catalog-store-service", url = "${services.catalog.base-url:http://localhost:8082}", configuration = CatalogFeignConfig.class)
public interface CatalogServiceClient {
    @GetMapping("/internal/v1/products/{productId}/variants/{variantId}/snapshot")
    ProductSnapshotResponse getProductSnapshot(@PathVariable Long productId, @PathVariable Long variantId);
    @GetMapping("/internal/v1/stores/{storeId}/summary") StoreSummaryResponse getStoreSummary(@PathVariable Long storeId);
}
