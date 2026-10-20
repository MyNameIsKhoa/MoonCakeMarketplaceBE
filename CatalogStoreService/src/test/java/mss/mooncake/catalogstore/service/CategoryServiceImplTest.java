package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
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
import mss.mooncake.catalogstore.service.impl.CategoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryServiceImpl Unit Tests")
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static CategoryEntity category(Long id, Long parentId, String slug, CategoryStatus status) {
        CategoryEntity c = new CategoryEntity();
        ReflectionTestUtils.setField(c, "id", id);
        c.setParentCategoryId(parentId);
        c.setName("Danh mục " + slug);
        c.setSlug(slug);
        c.setStatus(status);
        return c;
    }

    @Nested
    @DisplayName("getCategories / getCategory")
    class Read {

        @Test
        @DisplayName("không có parentCategoryId → trả danh mục gốc ACTIVE")
        void rootCategories() {
            var pageable = PageRequest.of(0, 20);
            when(categoryRepository.findByParentCategoryIdIsNullAndStatus(CategoryStatus.ACTIVE, pageable))
                    .thenReturn(new PageImpl<>(List.of(category(1L, null, "chay", CategoryStatus.ACTIVE)), pageable, 1));

            PageResponse<CategoryResponse> page = categoryService.getCategories(null, pageable);

            assertThat(page.items()).extracting(CategoryResponse::slug).containsExactly("chay");
            assertThat(page.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("parent không tồn tại hoặc INACTIVE → 404")
        void unknownParent() {
            when(categoryRepository.findByIdAndStatus(9L, CategoryStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.getCategories(9L, PageRequest.of(0, 20)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("danh mục INACTIVE không hiển thị public → 404")
        void inactiveCategoryHidden() {
            when(categoryRepository.findByIdAndStatus(1L, CategoryStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.getCategory(1L)).isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createCategory")
    class Create {

        @Test
        @DisplayName("tạo danh mục con ACTIVE")
        void createChild() {
            when(categoryRepository.existsBySlug("banh-deo")).thenReturn(false);
            when(categoryRepository.findByIdAndStatus(1L, CategoryStatus.ACTIVE))
                    .thenReturn(Optional.of(category(1L, null, "truyen-thong", CategoryStatus.ACTIVE)));
            when(categoryRepository.saveAndFlush(any(CategoryEntity.class))).thenAnswer(inv -> {
                CategoryEntity saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 10L);
                return saved;
            });

            CategoryResponse response = categoryService.createCategory(
                    new CreateCategoryRequest(" Bánh dẻo ", " banh-deo ", 1L));

            assertThat(response.categoryId()).isEqualTo(10L);
            assertThat(response.parentCategoryId()).isEqualTo(1L);
            assertThat(response.name()).isEqualTo("Bánh dẻo");
            assertThat(response.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("slug trùng → 409, không ghi DB")
        void duplicateSlug() {
            when(categoryRepository.existsBySlug("chay")).thenReturn(true);

            assertThatThrownBy(() -> categoryService.createCategory(new CreateCategoryRequest("Chay", "chay", null)))
                    .isInstanceOf(ConflictException.class);
            verify(categoryRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("hai request cùng slug: unique constraint của DB → 409")
        void concurrentDuplicateSlug() {
            when(categoryRepository.existsBySlug("chay")).thenReturn(false);
            when(categoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("UQ_category_slug"));

            assertThatThrownBy(() -> categoryService.createCategory(new CreateCategoryRequest("Chay", "chay", null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("parent không tồn tại → 404")
        void unknownParent() {
            when(categoryRepository.existsBySlug("con")).thenReturn(false);
            when(categoryRepository.findByIdAndStatus(99L, CategoryStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.createCategory(new CreateCategoryRequest("Con", "con", 99L)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("updateCategory")
    class Update {

        @Test
        @DisplayName("parent là chính nó → 400")
        void parentIsSelf() {
            CategoryEntity c = category(1L, null, "a", CategoryStatus.ACTIVE);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(c));
            when(categoryRepository.findByIdAndStatus(1L, CategoryStatus.ACTIVE)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> categoryService.updateCategory(1L, new UpdateCategoryRequest(null, null, 1L, null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("parent là cháu của nó (1 ← 2 ← 3, đặt cha của 1 là 3) → 400")
        void parentIsDescendant() {
            CategoryEntity c1 = category(1L, null, "a", CategoryStatus.ACTIVE);
            CategoryEntity c2 = category(2L, 1L, "b", CategoryStatus.ACTIVE);
            CategoryEntity c3 = category(3L, 2L, "c", CategoryStatus.ACTIVE);
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(c1));
            when(categoryRepository.findByIdAndStatus(3L, CategoryStatus.ACTIVE)).thenReturn(Optional.of(c3));
            when(categoryRepository.findById(3L)).thenReturn(Optional.of(c3));
            when(categoryRepository.findById(2L)).thenReturn(Optional.of(c2));

            assertThatThrownBy(() -> categoryService.updateCategory(1L, new UpdateCategoryRequest(null, null, 3L, null)))
                    .isInstanceOf(BadRequestException.class);
            verify(categoryRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("đổi parent hợp lệ, tên và status")
        void validUpdate() {
            CategoryEntity c3 = category(3L, null, "c", CategoryStatus.ACTIVE);
            CategoryEntity c1 = category(1L, null, "a", CategoryStatus.ACTIVE);
            when(categoryRepository.findById(3L)).thenReturn(Optional.of(c3));
            when(categoryRepository.findByIdAndStatus(1L, CategoryStatus.ACTIVE)).thenReturn(Optional.of(c1));
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(c1));
            when(categoryRepository.saveAndFlush(c3)).thenReturn(c3);

            CategoryResponse response = categoryService.updateCategory(3L,
                    new UpdateCategoryRequest("Tên mới", null, 1L, "inactive"));

            assertThat(response.parentCategoryId()).isEqualTo(1L);
            assertThat(response.name()).isEqualTo("Tên mới");
            assertThat(response.status()).isEqualTo("INACTIVE");
        }

        @Test
        @DisplayName("slug trùng danh mục khác → 409")
        void duplicateSlug() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, null, "a", CategoryStatus.ACTIVE)));
            when(categoryRepository.existsBySlugAndIdNot("chay", 1L)).thenReturn(true);

            assertThatThrownBy(() -> categoryService.updateCategory(1L, new UpdateCategoryRequest(null, "chay", null, null)))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("status không hợp lệ → 400")
        void invalidStatus() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, null, "a", CategoryStatus.ACTIVE)));

            assertThatThrownBy(() -> categoryService.updateCategory(1L, new UpdateCategoryRequest(null, null, null, "DELETED")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("tên toàn khoảng trắng → 400")
        void blankName() {
            when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, null, "a", CategoryStatus.ACTIVE)));

            assertThatThrownBy(() -> categoryService.updateCategory(1L, new UpdateCategoryRequest("   ", null, null, null)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("danh mục không tồn tại → 404")
        void notFound() {
            when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> categoryService.updateCategory(404L, new UpdateCategoryRequest("x", null, null, null)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
