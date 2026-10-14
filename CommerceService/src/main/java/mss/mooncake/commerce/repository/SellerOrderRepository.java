package mss.mooncake.commerce.repository;

import java.util.List;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SellerOrderRepository extends JpaRepository<SellerOrderEntity, Long> {

    List<SellerOrderEntity> findByOrderId(Long orderId);

    List<SellerOrderEntity> findBySupplierIdRef(Long supplierIdRef);
}
