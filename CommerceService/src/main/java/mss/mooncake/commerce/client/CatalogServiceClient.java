package mss.mooncake.commerce.client;

import mss.mooncake.commerce.client.dto.catalog.ProductSnapshotResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "catalog-store-service", url = "${services.catalog.base-url:http://localhost:8082}", configuration = CatalogFeignConfig.class)
public interface CatalogServiceClient {
    @GetMapping("/internal/v1/products/{productId}/variants/{variantId}/snapshot")
    ProductSnapshotResponse getProductSnapshot(@PathVariable Long productId, @PathVariable Long variantId);
}
