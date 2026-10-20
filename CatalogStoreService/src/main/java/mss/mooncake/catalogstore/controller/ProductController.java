package mss.mooncake.catalogstore.controller;

import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;
import mss.mooncake.catalogstore.service.CatalogApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/products")
public class ProductController {
    private final CatalogApplicationService catalogApplicationService;

    public ProductController(CatalogApplicationService catalogApplicationService) {
        this.catalogApplicationService = catalogApplicationService;
    }

    @GetMapping("/{productId}/variants/{variantId}/snapshot")
    public ResponseEntity<ProductSnapshotResponse> getSnapshot(@PathVariable Long productId,
            @PathVariable Long variantId) {
        return ResponseEntity.ok(catalogApplicationService.getProductSnapshot(productId, variantId));
    }
}
