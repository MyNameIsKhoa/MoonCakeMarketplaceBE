package mss.mooncake.commerce.repository;

import mss.mooncake.commerce.entity.DeliveryAddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DeliveryAddressRepository extends JpaRepository<DeliveryAddressEntity, Long> {
    Optional<DeliveryAddressEntity> findByOrderId(Long orderId);
}
