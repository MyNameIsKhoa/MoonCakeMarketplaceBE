package mss.mooncake.catalogstore.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<StoreEntity, Long> {

    List<StoreEntity> findBySupplierIdRef(Long supplierIdRef);

    List<StoreEntity> findByStatusOrderByIdAsc(StoreStatus status);

    List<StoreEntity> findBySupplierIdRefAndStatusOrderByIdAsc(Long supplierIdRef, StoreStatus status);

    Optional<StoreEntity> findByIdAndStatus(Long id, StoreStatus status);

    boolean existsByStoreCode(String storeCode);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, Long id);

    Optional<StoreEntity> findBySlug(String slug);
}
