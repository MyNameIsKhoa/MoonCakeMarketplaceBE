package mss.mooncake.catalogstore.repository;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Điều kiện search product (CA06). Filter null/rỗng được bỏ qua.
 */
public final class ProductSpecifications {

    private ProductSpecifications() { }

    // ─── Search use cases ─────────────────────────────────────────────────────────

    /** Public: chỉ product ACTIVE thuộc store ACTIVE. */
    public static Specification<ProductEntity> publicSearch(String q, Long storeId, Long categoryId) {
        List<Specification<ProductEntity>> specs = new ArrayList<>();
        specs.add(hasStatus(ProductStatus.ACTIVE));
        specs.add(storeHasStatus(StoreStatus.ACTIVE));
        addOptionalFilters(specs, q, storeId, categoryId);
        return Specification.allOf(specs);
    }

    /** Chủ store (mine=true): mọi trạng thái product trong các store của supplier. */
    public static Specification<ProductEntity> ownerSearch(Collection<Long> ownedStoreIds,
                                                           String q, Long storeId, Long categoryId) {
        List<Specification<ProductEntity>> specs = new ArrayList<>();
        specs.add(inStores(ownedStoreIds));
        addOptionalFilters(specs, q, storeId, categoryId);
        return Specification.allOf(specs);
    }

    private static void addOptionalFilters(List<Specification<ProductEntity>> specs,
                                           String q, Long storeId, Long categoryId) {
        if (storeId != null) specs.add(inStore(storeId));
        if (categoryId != null) specs.add(inCategory(categoryId));
        if (StringUtils.hasText(q)) specs.add(nameContains(q.trim()));
    }

    // ─── Điều kiện đơn ────────────────────────────────────────────────────────────

    public static Specification<ProductEntity> hasStatus(ProductStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<ProductEntity> inStore(Long storeId) {
        return (root, query, cb) -> cb.equal(root.get("storeId"), storeId);
    }

    /** Danh sách rỗng trả về điều kiện luôn sai, tránh sinh SQL "IN ()". */
    public static Specification<ProductEntity> inStores(Collection<Long> storeIds) {
        return (root, query, cb) -> storeIds.isEmpty() ? cb.disjunction() : root.get("storeId").in(storeIds);
    }

    public static Specification<ProductEntity> inCategory(Long categoryId) {
        return (root, query, cb) -> cb.equal(root.get("categoryId"), categoryId);
    }

    /** So khớp tên không phân biệt hoa thường; ký tự % _ của người dùng được escape. */
    public static Specification<ProductEntity> nameContains(String q) {
        String escaped = q.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), "%" + escaped + "%", '\\');
    }

    /** Product không có quan hệ JPA tới Store (chỉ giữ storeId) nên dùng subquery EXISTS. */
    public static Specification<ProductEntity> storeHasStatus(StoreStatus status) {
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
