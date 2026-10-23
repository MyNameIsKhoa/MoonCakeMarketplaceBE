package mss.mooncake.catalogstore.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import mss.mooncake.catalogstore.entity.StockReservationLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationLineRepository extends JpaRepository<StockReservationLineEntity, Long> {
    List<StockReservationLineEntity> findByReservationKeyOrderByVariantId(String reservationKey);

    @Query("""
            select coalesce(sum(l.quantity), 0) from StockReservationLineEntity l, StockReservationEntity r
            where l.reservationKey = r.reservationKey and l.variantId = :variantId
              and r.status = 'HELD' and r.expiresAt > :now
            """)
    Long heldQuantity(@Param("variantId") Long variantId, @Param("now") LocalDateTime now);

    /** Lượng đang giữ (HELD, chưa hết hạn) của nhiều variant trong một query; variant không có hold thì không có dòng. */
    @Query("""
            select l.variantId as variantId, sum(l.quantity) as quantity
            from StockReservationLineEntity l, StockReservationEntity r
            where l.reservationKey = r.reservationKey and l.variantId in :variantIds
              and r.status = 'HELD' and r.expiresAt > :now
            group by l.variantId
            """)
    List<HeldQuantity> heldQuantities(@Param("variantIds") Collection<Long> variantIds, @Param("now") LocalDateTime now);

    interface HeldQuantity {
        Long getVariantId();
        Long getQuantity();
    }
}
