package mss.mooncake.catalogstore.controller;

import java.util.List;
import mss.mooncake.catalogstore.dto.response.CategoryResponse;
import mss.mooncake.catalogstore.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản trị danh mục (ROLE_ADMIN, chặn ở SecurityConfig "/api/admin/catalog/**").
 * GET /api/admin/catalog/categories — mọi danh mục ở mọi trạng thái, danh sách phẳng có parentCategoryId,
 * sắp xếp theo name, id. Ghi (tạo/sửa/ẩn/hiện) vẫn qua POST/PATCH /api/categories.
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminCategoryController {
    private final CategoryService categories;

    public AdminCategoryController(CategoryService categories) { this.categories = categories; }

    @GetMapping("/categories")
    public List<CategoryResponse> listCategories() { return categories.getAllCategoriesForAdmin(); }
}
