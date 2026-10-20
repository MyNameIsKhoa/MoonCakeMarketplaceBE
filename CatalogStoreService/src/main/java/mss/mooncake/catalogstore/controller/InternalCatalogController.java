package mss.mooncake.catalogstore.controller;

import jakarta.validation.Valid;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.request.VariantSnapshotBatchRequest;
import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import mss.mooncake.catalogstore.service.CatalogApplicationService;
import mss.mooncake.catalogstore.service.PromotionCalculationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal contract catalog cho TV3 (Commerce) và TV4 (RFQ). Không expose qua Gateway.
 * Authentication: X-Service-Name + X-Service-Token (InternalServiceTokenFilter).
 * Tách khỏi ProductController để /api/products dành cho API public (CA06–CA14).
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalCatalogController {

    private final CatalogApplicationService catalogApplicationService;

    private final PromotionCalculationService promotionCalculationService;

    public InternalCatalogController(CatalogApplicationService catalogApplicationService,
                                     PromotionCalculationService promotionCalculationService) {
        this.catalogApplicationService = catalogApplicationService;
        this.promotionCalculationService = promotionCalculationService;
    }

    /** IN08 — tính khuyến mãi cho các line của một store; giá và thời điểm tính do Catalog quyết định. */
    @PostMapping("/promotions/calculate")
    public ResponseEntity<PromotionCalculationResult> calculatePromotions(
            @Valid @RequestBody PromotionCalculationRequest request) {
        return ResponseEntity.ok(promotionCalculationService.calculate(request));
    }

    /** IN06 — snapshot một variant. */
    @GetMapping("/products/{productId}/variants/{variantId}/snapshot")
    public ResponseEntity<ProductSnapshotResponse> getSnapshot(@PathVariable Long productId,
                                                               @PathVariable Long variantId) {
        return ResponseEntity.ok(catalogApplicationService.getProductSnapshot(productId, variantId));
    }

    /** IN07 — snapshot theo lô (tối đa 100), giữ thứ tự đầu vào. Dùng POST vì danh sách ID nằm trong body. */
    @PostMapping("/variants/snapshots")
    public ResponseEntity<List<VariantSnapshotResponse>> getVariantSnapshots(
            @Valid @RequestBody VariantSnapshotBatchRequest request) {
        return ResponseEntity.ok(catalogApplicationService.getVariantSnapshots(request.variantIds()));
    }
}
