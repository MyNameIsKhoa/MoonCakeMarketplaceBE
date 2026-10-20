package mss.mooncake.catalogstore.repository;

import mss.mooncake.catalogstore.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Search/listing (CA06) dùng {@link ProductSpecifications} với
 * {@code findAll(Specification, Pageable)} từ {@link JpaSpecificationExecutor}.
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long>,
    JpaSpecificationExecutor<ProductEntity> {

  boolean existsByStoreIdAndProductCode(Long storeId, String productCode);

  boolean existsByCategoryId(Long categoryId);
}
