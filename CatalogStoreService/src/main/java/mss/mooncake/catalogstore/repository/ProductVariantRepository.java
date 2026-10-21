package mss.mooncake.catalogstore.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository extends JpaRepository<ProductVariantEntity, Long> {

    Page<ProductVariantEntity> findByProductId(Long productId, Pageable pageable);

    Page<ProductVariantEntity> findByProductIdAndStatus(Long productId, ProductStatus status, Pageable pageable);

    /**
     * Lấy toàn bộ variant của nhiều product một lần, tránh N+1 khi build
     * list/detail.
     */
    List<ProductVariantEntity> findByProductIdInOrderByIdAsc(Collection<Long> productIds);

    /** Kiểm tra variant thuộc đúng product trong URL. */
    Optional<ProductVariantEntity> findByIdAndProductId(Long id, Long productId);

    List<ProductVariantEntity> findByIdIn(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProductVariantEntity v where v.id in :ids order by v.id")
    List<ProductVariantEntity> lockByIds(@Param("ids") Collection<Long> ids);

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsByProductIdAndStatus(Long productId, ProductStatus status);

    /**
     * Đếm số variant trong {@code variantIds} thuộc store {@code storeId}.
     * Dùng khi lưu promotion rule: kết quả khác số ID truyền vào nghĩa là có
     * variant ngoài store.
     */
    @Query("""
            select count(v) from ProductVariantEntity v, ProductEntity p
            where p.id = v.productId and p.storeId = :storeId and v.id in :variantIds
            """)
    long countInStore(@Param("storeId") Long storeId, @Param("variantIds") Collection<Long> variantIds);
}
