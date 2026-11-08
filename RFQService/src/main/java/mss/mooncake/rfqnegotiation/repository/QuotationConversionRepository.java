package mss.mooncake.rfqnegotiation.repository;
import java.util.List;
import java.util.Optional;
import mss.mooncake.rfqnegotiation.entity.QuotationConversionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface QuotationConversionRepository extends JpaRepository<QuotationConversionEntity, Long> {
    Optional<QuotationConversionEntity> findByQuotationId(Long quotationId);
    /** A FAILED intent no longer locks the quotation: Commerce refused it and no order exists. */
    boolean existsByQuotationIdAndStatusNot(Long quotationId, String status);
    Optional<QuotationConversionEntity> findByCustomerIdRefAndIdempotencyKey(Long customerId, String key);
    List<QuotationConversionEntity> findByStatusOrderByUpdatedAtAsc(String status, Pageable pageable);

    /** Units an RFQ has committed: items of quotations whose acceptance is pending or done and whose order was not cancelled. */
    @Query("""
            select coalesce(sum(i.quantity), 0) from QuotationConversionEntity c, QuotationEntity q, QuotationItemEntity i
            where c.quotationId = q.id and i.quotationId = q.id and q.rfqId = :rfqId
              and c.status in ('PENDING', 'COMPLETED')
              and q.status <> mss.mooncake.rfqnegotiation.enums.QuotationStatus.ORDER_CANCELLED
            """)
    long committedQuantity(@Param("rfqId") Long rfqId);

    /** Orders that can still be cancelled in Commerce, least recently checked first. */
    @Query("""
            select c from QuotationConversionEntity c
            where c.status = 'COMPLETED' and (c.orderStatus is null or c.orderStatus not in ('CANCELLED', 'SETTLED'))
            order by case when c.orderCheckedAt is null then 0 else 1 end, c.orderCheckedAt, c.id
            """)
    List<QuotationConversionEntity> findOrdersToSync(Pageable pageable);
}
