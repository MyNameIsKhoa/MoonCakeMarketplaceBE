package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.ConversationEntity;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<ConversationEntity, Long> {
    Optional<ConversationEntity> findByRfqIdAndSupplierIdRef(Long rfqId, Long supplierId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConversationEntity c where c.id = :id")
    Optional<ConversationEntity> findByIdForUpdate(@Param("id") Long id);

    @Query("select c from ConversationEntity c where (:rfqId is null or c.rfqId = :rfqId) "
            + "and (c.customerIdRef = :customerId or c.supplierIdRef = :supplierId)")
    Page<ConversationEntity> findForParticipant(@Param("customerId") Long customerId,
            @Param("supplierId") Long supplierId, @Param("rfqId") Long rfqId, Pageable pageable);
}
