package mss.mooncake.commerce.repository;

import java.util.Collection;
import java.util.List;
import mss.mooncake.commerce.entity.ShipmentLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ShipmentLineRepository extends JpaRepository<ShipmentLineEntity, Long> {

    List<ShipmentLineEntity> findByShipmentIdIn(Collection<Long> shipmentIds);
}
