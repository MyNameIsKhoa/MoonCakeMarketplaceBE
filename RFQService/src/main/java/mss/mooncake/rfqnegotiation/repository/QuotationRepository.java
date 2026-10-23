package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotationRepository extends JpaRepository<QuotationEntity, Long>, JpaSpecificationExecutor<QuotationEntity> {
    boolean existsByRfqId(Long rfqId);

    Optional<QuotationEntity> findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(Long rfqId, Long storeIdRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuotationEntity q where q.id = :id")
    Optional<QuotationEntity> findByIdForUpdate(@Param("id") Long id);
}
