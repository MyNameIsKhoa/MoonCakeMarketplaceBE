package mss.mooncake.catalogstore.repository;

import java.time.LocalDateTime;
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
}
