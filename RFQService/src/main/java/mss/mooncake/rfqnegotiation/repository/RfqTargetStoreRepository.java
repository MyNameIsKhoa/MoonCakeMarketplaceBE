package mss.mooncake.rfqnegotiation.repository;
import java.util.List;
import mss.mooncake.rfqnegotiation.entity.RfqTargetStoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqTargetStoreRepository extends JpaRepository<RfqTargetStoreEntity, Long> {
    List<RfqTargetStoreEntity> findByRfqIdOrderByIdAsc(Long rfqId);

    boolean existsByRfqIdAndSupplierIdRef(Long rfqId, Long supplierIdRef);

    boolean existsByRfqIdAndStoreIdRef(Long rfqId, Long storeIdRef);
}
