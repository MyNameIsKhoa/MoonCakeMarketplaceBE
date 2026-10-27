package mss.mooncake.catalogstore.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import mss.mooncake.catalogstore.entity.StockReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRepository extends JpaRepository<StockReservationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservationEntity r where r.reservationKey = :key")
    Optional<StockReservationEntity> lockByKey(@Param("key") String key);
}
