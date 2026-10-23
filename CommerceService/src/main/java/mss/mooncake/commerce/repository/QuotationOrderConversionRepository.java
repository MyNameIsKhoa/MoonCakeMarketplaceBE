package mss.mooncake.commerce.repository;
import java.util.Optional;
import mss.mooncake.commerce.entity.QuotationOrderConversionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface QuotationOrderConversionRepository extends JpaRepository<QuotationOrderConversionEntity,Long> {
    Optional<QuotationOrderConversionEntity> findByQuotationIdRef(Long quotationId);
    Optional<QuotationOrderConversionEntity> findByIdempotencyKey(String key);
}
