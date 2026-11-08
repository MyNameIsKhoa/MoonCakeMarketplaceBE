package mss.mooncake.catalogstore.repository;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import mss.mooncake.catalogstore.entity.CategoryEntity;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.CategoryStatus;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.util.StringUtils;

/**
 * Search/listing (CA06): gọi {@code findAll(ProductRepository.publicSearch(...), pageable)}.
 * Các điều kiện Specification là static method của interface (Spring Data không suy query từ static method).
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>,
    JpaSpecificationExecutor<ProductEntity> {

  boolean existsByStoreIdAndProductCode(Long storeId, String productCode);

  boolean existsByCategoryId(Long categoryId);

  // ─── Specification cho search (CA06); filter null/rỗng được bỏ qua ─────────────

  /** Public: chỉ product ACTIVE thuộc store ACTIVE và không thuộc danh mục đã bị ẩn (INACTIVE). */
  static Specification<ProductEntity> publicSearch(String q, Long storeId, Long categoryId) {
    List<Specification<ProductEntity>> specs = new ArrayList<>();
    specs.add((root, query, cb) -> cb.equal(root.get("status"), ProductStatus.ACTIVE));
    specs.add(storeHasStatus(StoreStatus.ACTIVE));
    specs.add(categoryNotHidden());
    addOptionalFilters(specs, q, storeId, categoryId);
    return Specification.allOf(specs);
  }

  /** Chủ store (mine=true): mọi trạng thái product trong các store của supplier. */
  static Specification<ProductEntity> ownerSearch(Collection<Long> ownedStoreIds,
      String q, Long storeId, Long categoryId) {
    List<Specification<ProductEntity>> specs = new ArrayList<>();
    // Danh sách rỗng trả về điều kiện luôn sai, tránh sinh SQL "IN ()".
    specs.add((root, query, cb) -> ownedStoreIds.isEmpty() ? cb.disjunction() : root.get("storeId").in(ownedStoreIds));
    addOptionalFilters(specs, q, storeId, categoryId);
    return Specification.allOf(specs);
  }

  private static void addOptionalFilters(List<Specification<ProductEntity>> specs,
      String q, Long storeId, Long categoryId) {
    if (storeId != null) specs.add((root, query, cb) -> cb.equal(root.get("storeId"), storeId));
    if (categoryId != null) specs.add((root, query, cb) -> cb.equal(root.get("categoryId"), categoryId));
    if (StringUtils.hasText(q)) specs.add(nameContains(q.trim()));
  }

  /** So khớp tên không phân biệt hoa thường; ký tự % _ của người dùng được escape. */
  private static Specification<ProductEntity> nameContains(String q) {
    String escaped = q.toLowerCase()
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_");
    return (root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\');
  }

  /** NOT EXISTS danh mục của product có trạng thái khác ACTIVE (product không có quan hệ JPA tới Category). */
  private static Specification<ProductEntity> categoryNotHidden() {
    return (root, query, cb) -> {
      Subquery<Long> sub = query.subquery(Long.class);
      Root<CategoryEntity> category = sub.from(CategoryEntity.class);
      sub.select(category.get("id"))
          .where(cb.equal(category.get("id"), root.get("categoryId")),
              cb.notEqual(category.get("status"), CategoryStatus.ACTIVE));
      return cb.not(cb.exists(sub));
    };
  }

  /** Product không có quan hệ JPA tới Store (chỉ giữ storeId) nên dùng subquery EXISTS. */
  private static Specification<ProductEntity> storeHasStatus(StoreStatus status) {
    return (root, query, cb) -> {
      Subquery<Long> sub = query.subquery(Long.class);
      Root<StoreEntity> store = sub.from(StoreEntity.class);
      sub.select(store.get("id"))
          .where(cb.equal(store.get("id"), root.get("storeId")),
              cb.equal(store.get("status"), status));
      return cb.exists(sub);
    };
  }
}
