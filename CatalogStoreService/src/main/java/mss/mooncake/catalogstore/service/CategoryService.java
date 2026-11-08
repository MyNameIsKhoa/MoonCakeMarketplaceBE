package mss.mooncake.catalogstore.service;

import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateCategoryRequest;
import mss.mooncake.catalogstore.dto.request.UpdateCategoryRequest;
import mss.mooncake.catalogstore.dto.response.CategoryResponse;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

/**
 * Danh mục dùng chung toàn sàn (CA01–CA04). Đọc public, ghi chỉ ROLE_ADMIN
 * (chặn ở SecurityConfig).
 */
public interface CategoryService {

    /**
     * CA01 — danh mục ACTIVE. parentCategoryId null: danh mục gốc; khác null: con
     * trực tiếp.
     */
    PageResponse<CategoryResponse> getCategories(Long parentCategoryId, Pageable pageable);

    /**
     * CA02 — chi tiết danh mục ACTIVE; INACTIVE hoặc không tồn tại trả 404.
     */
    CategoryResponse getCategory(Long categoryId);

    /** Admin — mọi danh mục ở mọi trạng thái (danh sách phẳng, có parentCategoryId để dựng cây). */
    List<CategoryResponse> getAllCategoriesForAdmin();

    /** Admin — chi tiết danh mục bất kể trạng thái; không tồn tại trả 404. */
    CategoryResponse getCategoryForAdmin(Long categoryId);

    /**
     * CA03 — tạo danh mục ACTIVE. Slug trùng trả 409; parent phải tồn tại.
     */
    CategoryResponse createCategory(CreateCategoryRequest request);

    /**
     * CA04 — cập nhật danh mục. Không cho parent là chính nó hoặc con cháu của nó
     * (chặn chu trình).
     */
    CategoryResponse updateCategory(Long categoryId, UpdateCategoryRequest request);
}
