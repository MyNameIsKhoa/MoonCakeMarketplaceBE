package mss.mooncake.catalogstore.controller;

import jakarta.validation.Valid;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateStoreRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreRequest;
import mss.mooncake.catalogstore.dto.response.StoreResponse;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.StoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API cho Store.
 *
 * GET  /api/stores          — public storefront listing (có thể filter by ?mine=true để lấy store của supplier)
 * GET  /api/stores/{id}     — public chi tiết store
 * POST /api/stores          — tạo store (yêu cầu JWT + supplier)
 * PATCH /api/stores/{id}    — cập nhật branding (yêu cầu JWT + owner)
 */
@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    /**
     * GET /api/stores
     * - Không có param: trả tất cả store (public storefront listing).
     * - ?mine=true: trả store của supplier đang login (yêu cầu JWT).
     * - ?supplierId=...: lọc store theo supplier_id.
     */
    @GetMapping
    public ResponseEntity<List<StoreResponse>> getStores(
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false) Long supplierId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (mine) {
            if (principal == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            return ResponseEntity.ok(storeService.getMyStores(principal.getUserId()));
        }
        if (supplierId != null) {
            return ResponseEntity.ok(storeService.getStoresBySupplierId(supplierId));
        }
        return ResponseEntity.ok(storeService.getAllStores());
    }

    /**
     * GET /api/stores/{id} — ACTIVE công khai; owner đăng nhập xem được store chưa công bố.
     */
    @GetMapping("/{id}")
    public ResponseEntity<StoreResponse> getStoreByIdOrSlug(@PathVariable("id") String id,
                                                            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(storeService.getStoreByIdOrSlug(id, principal == null ? null : principal.getUserId()));
    }

    /**
     * POST /api/stores — tạo store, chỉ supplier.
     * userId lấy từ JWT principal, không nhận từ body (tuân thủ mục 1 TODO_IMPLEMENTATION).
     */
    @PostMapping
    public ResponseEntity<StoreResponse> createStore(
            @Valid @RequestBody CreateStoreRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        StoreResponse response = storeService.createStore(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * PATCH /api/stores/{id} — cập nhật branding, chỉ owner.
     * Ownership check trong service: store.supplierIdRef === supplier(userId).supplierId.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<StoreResponse> updateStore(
            @PathVariable("id") Long id,
            @Valid @RequestBody UpdateStoreRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        boolean admin = principal.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        StoreResponse response = storeService.updateStore(id, request, principal.getUserId(), admin);
        return ResponseEntity.ok(response);
    }
}
