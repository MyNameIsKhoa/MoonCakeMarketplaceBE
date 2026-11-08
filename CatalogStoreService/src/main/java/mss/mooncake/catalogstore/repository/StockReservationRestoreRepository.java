package mss.mooncake.catalogstore.repository;

import java.util.List;
import mss.mooncake.catalogstore.entity.StockReservationRestoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRestoreRepository extends JpaRepository<StockReservationRestoreEntity, Long> {
    List<StockReservationRestoreEntity> findByRestoreKeyOrderByVariantId(String restoreKey);

    /** Units of each variant of the reservation already returned to stock line by line. */
    @Query("""
            select r.variantId as variantId, sum(r.quantity) as quantity
            from StockReservationRestoreEntity r where r.reservationKey = :reservationKey
            group by r.variantId
            """)
    List<RestoredQuantity> restoredQuantities(@Param("reservationKey") String reservationKey);

    interface RestoredQuantity {
        Long getVariantId();
        Long getQuantity();
    }
}
