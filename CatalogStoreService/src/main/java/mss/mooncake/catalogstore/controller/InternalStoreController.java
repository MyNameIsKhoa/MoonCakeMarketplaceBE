package mss.mooncake.catalogstore.controller;

import mss.mooncake.catalogstore.dto.response.StoreSummaryResponse;
import mss.mooncake.catalogstore.service.StoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal contract endpoint cho TV3 (Commerce) và TV4 (RFQ).
 * Không expose ra Gateway public routing.
 * Authentication: X-Service-Token dành cho lời gọi service-to-service.
 */
@RestController
@RequestMapping("/internal/v1/stores")
public class InternalStoreController {

    private final StoreService storeService;

    public InternalStoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    /**
     * GET /internal/v1/stores/{id}/summary
     * Trả StoreSummaryResponse để TV3/TV4 snapshot vào order/quotation.
     */
    @GetMapping("/{id}/summary")
    public ResponseEntity<StoreSummaryResponse> getStoreSummary(@PathVariable("id") Long id) {
        return ResponseEntity.ok(storeService.getStoreSummary(id));
    }
}
