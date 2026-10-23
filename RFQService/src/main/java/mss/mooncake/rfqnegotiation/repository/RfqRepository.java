package mss.mooncake.rfqnegotiation.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqRepository extends JpaRepository<RfqEntity, Long>, JpaSpecificationExecutor<RfqEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RfqEntity r where r.id = :id")
    Optional<RfqEntity> findByIdForUpdate(@Param("id") Long id);
}
