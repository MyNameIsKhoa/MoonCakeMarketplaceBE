package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CartEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
public interface CartRepository extends JpaRepository<CartEntity, Long> {
    Optional<CartEntity> findFirstByCustomerIdRefAndStatusOrderByIdDesc(Long customerIdRef, String status);
    boolean existsByCustomerIdRefAndStatus(Long customerIdRef, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CartEntity c where c.id=:id and c.status='ACTIVE'")
    Optional<CartEntity> lockActive(@Param("id") Long id);
}
