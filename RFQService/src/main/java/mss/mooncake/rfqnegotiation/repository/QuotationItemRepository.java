package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.QuotationItemEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotationItemRepository extends JpaRepository<QuotationItemEntity, Long> {
    List<QuotationItemEntity> findByQuotationIdOrderByIdAsc(Long quotationId);

    @Query("select coalesce(sum(i.quantity), 0) from QuotationItemEntity i where i.quotationId = :quotationId")
    long quantityOf(@Param("quotationId") Long quotationId);
}
