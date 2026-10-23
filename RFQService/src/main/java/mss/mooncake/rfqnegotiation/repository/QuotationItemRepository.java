package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.QuotationItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotationItemRepository extends JpaRepository<QuotationItemEntity, Long> {
    List<QuotationItemEntity> findByQuotationIdOrderByIdAsc(Long quotationId);
}
