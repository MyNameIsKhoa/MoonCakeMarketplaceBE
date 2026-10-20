package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.request.CreateProductRequest;
import mss.mooncake.catalogstore.dto.request.UpdateProductRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.ProductResponse;
import mss.mooncake.catalogstore.dto.response.ProductSummaryResponse;
import org.springframework.data.domain.Pageable;

/**
 * Product của store (CA06–CA09). userId luôn lấy từ JWT principal, không nhận
 * từ body.
 */
public interface ProductService {

    /**
     * CA06 — search/listing.
     * mine=false: chỉ product ACTIVE thuộc store ACTIVE (public).
     * mine=true: mọi trạng thái trong các store của supplier đang login; userId bắt
     * buộc.
     *
     * @param q chuỗi tìm theo tên, null = không lọc
     */
    PageResponse<ProductSummaryResponse> searchProducts(String q, Long storeId, Long categoryId,
            boolean mine, Long userId, Pageable pageable);

    /**
     * CA07 — chi tiết product kèm variants.
     * Product/store chưa công bố chỉ owner xem được; người khác nhận 404.
     *
     * @param userId null nếu request không đăng nhập
     */
    ProductResponse getProduct(Long productId, Long userId);

    /**
     * CA08 — tạo product ở trạng thái DRAFT. Owner store; category ACTIVE;
     * server sinh productCode unique trong store.
     */
    ProductResponse createProduct(CreateProductRequest request, Long userId);

    /**
     * CA09 — cập nhật product. Owner store; không đổi storeId/productCode.
     * Chuyển sang ACTIVE yêu cầu có ít nhất một variant ACTIVE.
     */
    ProductResponse updateProduct(Long productId, UpdateProductRequest request, Long userId);
}
