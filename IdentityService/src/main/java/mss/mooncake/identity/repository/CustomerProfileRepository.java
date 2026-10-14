package mss.mooncake.identity.repository;

import java.util.Optional;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfileEntity, Long> {
    Optional<CustomerProfileEntity> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CustomerProfileEntity c WHERE c.userId = :userId")
    Optional<CustomerProfileEntity> findByUserIdForUpdate(@Param("userId") Long userId);
}
