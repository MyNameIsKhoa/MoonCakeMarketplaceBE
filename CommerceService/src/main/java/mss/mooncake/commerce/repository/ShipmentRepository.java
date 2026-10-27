package mss.mooncake.commerce.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.commerce.entity.ShipmentEntity;
import mss.mooncake.commerce.enums.ShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShipmentRepository extends JpaRepository<ShipmentEntity, Long> {

    List<ShipmentEntity> findBySellerOrderId(Long sellerOrderId);

    Optional<ShipmentEntity> findBySellerOrderIdAndStatus(Long sellerOrderId, ShipmentStatus status);

    boolean existsBySellerOrderIdAndStatus(Long sellerOrderId, ShipmentStatus status);
}
