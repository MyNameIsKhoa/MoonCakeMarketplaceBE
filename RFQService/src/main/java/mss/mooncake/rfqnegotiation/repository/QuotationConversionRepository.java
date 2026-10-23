package mss.mooncake.rfqnegotiation.repository;
import java.util.List;
import java.util.Optional;
import mss.mooncake.rfqnegotiation.entity.QuotationConversionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuotationConversionRepository extends JpaRepository<QuotationConversionEntity, Long> {
    Optional<QuotationConversionEntity> findByQuotationId(Long quotationId);
    /** A FAILED intent no longer locks the quotation: Commerce refused it and no order exists. */
    boolean existsByQuotationIdAndStatusNot(Long quotationId, String status);
    Optional<QuotationConversionEntity> findByCustomerIdRefAndIdempotencyKey(Long customerId, String key);
    List<QuotationConversionEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);
}
