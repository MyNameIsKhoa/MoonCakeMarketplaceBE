package mss.mooncake.catalogstore.repository;

import java.util.Optional;
import mss.mooncake.catalogstore.entity.CategoryEntity;
import mss.mooncake.catalogstore.enums.CategoryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> {

    /** Danh mục gốc (không có cha). */
    Page<CategoryEntity> findByParentCategoryIdIsNullAndStatus(CategoryStatus status, Pageable pageable);

    /** Danh mục con trực tiếp của một danh mục cha. */
    Page<CategoryEntity> findByParentCategoryIdAndStatus(Long parentCategoryId, CategoryStatus status,
            Pageable pageable);

    Optional<CategoryEntity> findByIdAndStatus(Long id, CategoryStatus status);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    /** Dùng khi xóa/vô hiệu danh mục: có danh mục con thì không cascade. */
    boolean existsByParentCategoryId(Long parentCategoryId);
}
