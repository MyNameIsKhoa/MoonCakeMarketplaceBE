package mss.mooncake.catalogstore.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import mss.mooncake.catalogstore.dto.request.CreateCategoryRequest;
import mss.mooncake.catalogstore.dto.request.UpdateCategoryRequest;
import mss.mooncake.catalogstore.dto.response.CategoryResponse;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.CategoryService;
import mss.mooncake.catalogstore.dto.request.PageQuery;
import org.springframework.data.domain.Sort;
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
 * Public API cho Category (TV2).
 *
 * GET   /api/categories                 — CA01 public, lọc theo parentCategoryId, phân trang
 * GET   /api/categories/{categoryId}    — CA02 public
 * POST  /api/categories                 — CA03 chỉ ROLE_ADMIN (chặn ở SecurityConfig)
 * PATCH /api/categories/{categoryId}    — CA04 chỉ ROLE_ADMIN
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private static final Set<String> SORT_FIELDS = Set.of("name", "slug", "id");
    private static final Sort DEFAULT_SORT = Sort.by("name");

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CategoryResponse>> getCategories(
            @RequestParam(required = false) Long parentCategoryId,
            PageQuery pageQuery) {
        return ResponseEntity.ok(categoryService.getCategories(parentCategoryId,
                pageQuery.toPageable(SORT_FIELDS, DEFAULT_SORT)));
    }

    /** Public chỉ thấy ACTIVE; admin (JWT ROLE_ADMIN) xem được mọi trạng thái để quản trị. */
    @GetMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> getCategory(@PathVariable Long categoryId,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        boolean admin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return ResponseEntity.ok(admin ? categoryService.getCategoryForAdmin(categoryId)
                : categoryService.getCategory(categoryId));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        CategoryResponse response = categoryService.createCategory(request);
        return ResponseEntity.created(URI.create("/api/categories/" + response.categoryId())).body(response);
    }

    @PatchMapping("/{categoryId}")
    public ResponseEntity<CategoryResponse> updateCategory(@PathVariable Long categoryId,
                                                           @Valid @RequestBody UpdateCategoryRequest request) {
        return ResponseEntity.ok(categoryService.updateCategory(categoryId, request));
    }
}
