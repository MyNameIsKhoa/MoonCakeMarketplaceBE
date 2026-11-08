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
    boolean existsByRfqIdAndSupplierIdRefAndStatus(Long rfqId, Long supplierIdRef, mss.mooncake.rfqnegotiation.enums.QuotationStatus status);
    java.util.List<QuotationEntity> findByRfqIdAndStatus(Long rfqId, mss.mooncake.rfqnegotiation.enums.QuotationStatus status);

    /** An accepted quotation, or one whose acceptance is still turning into an order. */
    @Query("""
            select count(q) > 0 from QuotationEntity q where q.rfqId = :rfqId
              and (q.status = mss.mooncake.rfqnegotiation.enums.QuotationStatus.ACCEPTED
                or exists (select c.id from QuotationConversionEntity c where c.quotationId = q.id and c.status = 'PENDING'))
            """)
    boolean hasLiveAcceptance(@Param("rfqId") Long rfqId);

    Optional<QuotationEntity> findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(Long rfqId, Long storeIdRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from QuotationEntity q where q.id = :id")
    Optional<QuotationEntity> findByIdForUpdate(@Param("id") Long id);
}
