package mss.mooncake.catalogstore.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.entity.StoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<StoreEntity, Long> {

    List<StoreEntity> findBySupplierIdRef(Long supplierIdRef);

    boolean existsByStoreCode(String storeCode);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Optional<StoreEntity> findBySlug(String slug);
}
