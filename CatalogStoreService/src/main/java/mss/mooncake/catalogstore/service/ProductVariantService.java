package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.request.CreateVariantRequest;
import mss.mooncake.catalogstore.dto.request.UpdateVariantRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.VariantResponse;
import org.springframework.data.domain.Pageable;

/**
 * Variant của product (CA11–CA14). Mọi thao tác kiểm tra variant thuộc đúng
 * productId trong URL.
 */
public interface ProductVariantService {

    /**
     * CA11 — public chỉ thấy variant ACTIVE của product công bố; owner thấy mọi
     * variant.
     *
     * @param userId null nếu request không đăng nhập
     */
    PageResponse<VariantResponse> getVariants(Long productId, Long userId, Pageable pageable);

    /**
     * CA12 — chi tiết variant; sai productId hoặc không có quyền xem trả 404.
     */
    VariantResponse getVariant(Long productId, Long variantId, Long userId);

    /**
     * CA13 — tạo variant ở trạng thái DRAFT. Owner store; SKU trùng trả 409;
     * giá/tồn kho không âm.
     */
    VariantResponse createVariant(Long productId, CreateVariantRequest request, Long userId);

    /**
     * CA14 — cập nhật variant. Owner store; không reparent; SKU mới trùng trả 409.
     */
    VariantResponse updateVariant(Long productId, Long variantId, UpdateVariantRequest request, Long userId);
}
