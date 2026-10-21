package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CartEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface CartRepository extends JpaRepository<CartEntity, Long> {
    Optional<CartEntity> findFirstByCustomerIdRefAndStatusOrderByIdDesc(Long customerIdRef, String status);
}
