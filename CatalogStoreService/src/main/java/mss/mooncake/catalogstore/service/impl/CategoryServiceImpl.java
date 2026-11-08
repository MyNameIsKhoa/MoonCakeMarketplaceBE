package mss.mooncake.catalogstore.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mss.mooncake.catalogstore.dto.request.CreateCategoryRequest;
import mss.mooncake.catalogstore.dto.request.UpdateCategoryRequest;
import mss.mooncake.catalogstore.dto.response.CategoryResponse;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.entity.CategoryEntity;
import mss.mooncake.catalogstore.enums.CategoryStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.CategoryRepository;
import mss.mooncake.catalogstore.service.CategoryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryServiceImpl(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    // ─── Helpers
    // ──────────────────────────────────────────────────────────────────

    private CategoryResponse toResponse(CategoryEntity e) {
        return new CategoryResponse(e.getId(), e.getParentCategoryId(), e.getName(), e.getSlug(), e.getStatus().name());
    }

    private CategoryEntity requireActive(Long categoryId) {
        return categoryRepository.findByIdAndStatus(categoryId, CategoryStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục với id: " + categoryId));
    }

    private CategoryStatus parseStatus(String status) {
        try {
            return CategoryStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Trạng thái danh mục không hợp lệ: " + status);
        }
    }

    /**
     * Đi ngược từ parent mới lên gốc; gặp lại chính categoryId nghĩa là tạo chu
     * trình.
     */
    private void ensureNoCycle(Long categoryId, Long newParentId) {
        Set<Long> visited = new HashSet<>();
        Long current = newParentId;
        while (current != null) {
            if (current.equals(categoryId)) {
                throw new BadRequestException("Danh mục cha không được là chính nó hoặc danh mục con của nó.");
            }
            if (!visited.add(current))
                break; // dữ liệu cũ đã có vòng; dừng để không lặp vô hạn
            current = categoryRepository.findById(current)
                    .map(CategoryEntity::getParentCategoryId)
                    .orElse(null);
        }
    }

    /**
     * Flush ngay để bắt vi phạm UQ_category_slug khi hai request cùng tạo một slug.
     */
    private CategoryEntity saveUniqueSlug(CategoryEntity category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Slug danh mục đã tồn tại: " + category.getSlug());
        }
    }

    // ─── Public API
    // ───────────────────────────────────────────────────────────────

    @Override
    public PageResponse<CategoryResponse> getCategories(Long parentCategoryId, Pageable pageable) {
        if (parentCategoryId == null) {
            return PageResponse.of(categoryRepository
                    .findByParentCategoryIdIsNullAndStatus(CategoryStatus.ACTIVE, pageable), this::toResponse);
        }
        requireActive(parentCategoryId);
        return PageResponse.of(categoryRepository
                .findByParentCategoryIdAndStatus(parentCategoryId, CategoryStatus.ACTIVE, pageable), this::toResponse);
    }

    @Override
    public CategoryResponse getCategory(Long categoryId) {
        return toResponse(requireActive(categoryId));
    }

    @Override
    public List<CategoryResponse> getAllCategoriesForAdmin() {
        return categoryRepository.findAll(Sort.by("name").and(Sort.by("id"))).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public CategoryResponse getCategoryForAdmin(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục với id: " + categoryId));
    }

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        String slug = request.slug().trim();
        if (categoryRepository.existsBySlug(slug)) {
            throw new ConflictException("Slug danh mục đã tồn tại: " + slug);
        }
        if (request.parentCategoryId() != null) {
            requireActive(request.parentCategoryId());
        }

        CategoryEntity category = new CategoryEntity();
        category.setParentCategoryId(request.parentCategoryId());
        category.setName(request.name().trim());
        category.setSlug(slug);
        category.setStatus(CategoryStatus.ACTIVE);
        return toResponse(saveUniqueSlug(category));
    }

    @Override
    @Transactional
    public CategoryResponse updateCategory(Long categoryId, UpdateCategoryRequest request) {
        // Admin được sửa cả danh mục INACTIVE nên không lọc theo status.
        CategoryEntity category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục với id: " + categoryId));

        if (request.name() != null) {
            if (!StringUtils.hasText(request.name()))
                throw new BadRequestException("Tên danh mục không được để trống.");
            category.setName(request.name().trim());
        }
        if (request.slug() != null) {
            String slug = request.slug().trim();
            if (categoryRepository.existsBySlugAndIdNot(slug, categoryId)) {
                throw new ConflictException("Slug danh mục đã tồn tại: " + slug);
            }
            category.setSlug(slug);
        }
        // parentCategoryId null = giữ nguyên (PATCH record chưa phân biệt vắng/null nên
        // chưa hỗ trợ đưa về gốc).
        if (request.parentCategoryId() != null) {
            requireActive(request.parentCategoryId());
            ensureNoCycle(categoryId, request.parentCategoryId());
            category.setParentCategoryId(request.parentCategoryId());
        }
        if (request.status() != null) {
            category.setStatus(parseStatus(request.status()));
        }
        return toResponse(saveUniqueSlug(category));
    }
}
