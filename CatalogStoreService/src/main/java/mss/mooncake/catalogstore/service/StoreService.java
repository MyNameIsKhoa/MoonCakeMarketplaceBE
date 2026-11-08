package mss.mooncake.catalogstore.service;

import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateStoreRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreRequest;
import mss.mooncake.catalogstore.dto.response.StoreResponse;
import mss.mooncake.catalogstore.dto.response.StoreSummaryResponse;

public interface StoreService {

    /**
     * Tạo store mới. Chỉ user đã là supplier mới được gọi.
     * Xác minh supplier qua IdentityClient; sinh store_code và slug duy nhất.
     *
     * @param request payload
     * @param userId  userId từ JWT principal (không nhận từ body)
     */
    StoreResponse createStore(CreateStoreRequest request, Long userId);

    /**
     * Lấy danh sách store của supplier đang login.
     *
     * @param userId userId từ JWT principal
     */
    List<StoreResponse> getMyStores(Long userId);

    /**
     * Lấy tất cả store (public, phục vụ storefront listing).
     */
    List<StoreResponse> getAllStores();
    List<StoreResponse> getAllStoresForAdmin();

    /**
     * Lấy danh sách store theo supplierId.
     */
    List<StoreResponse> getStoresBySupplierId(Long supplierId);

    /**
     * Lấy chi tiết một store theo storeId (public).
     */
    StoreResponse getStoreById(Long storeId);

    /**
     * Lấy chi tiết theo ID/slug; chỉ ACTIVE công khai, store khác chỉ chủ sở hữu
     * xem được.
     */
    StoreResponse getStoreByIdOrSlug(String identifier, Long userId);

    /**
     * Cập nhật branding/giao diện store.
     * Kiểm tra ownership: store.supplierIdRef phải khớp với supplierId của userId.
     *
     * @param storeId store cần cập nhật
     * @param request payload patch
     * @param userId  userId từ JWT principal
     */
    StoreResponse updateStore(Long storeId, UpdateStoreRequest request, Long userId);

    StoreResponse updateStore(Long storeId, UpdateStoreRequest request, Long userId, boolean admin);

    // ─── Internal contract ───────────────────────────────────────────────────────

    /**
     * Internal summary cho TV3/TV4 snapshot.
     */
    StoreSummaryResponse getStoreSummary(Long storeId);
}
