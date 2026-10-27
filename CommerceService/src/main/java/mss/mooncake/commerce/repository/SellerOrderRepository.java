package mss.mooncake.commerce.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SellerOrderRepository extends JpaRepository<SellerOrderEntity, Long> {

    List<SellerOrderEntity> findByOrderId(Long orderId);

    List<SellerOrderEntity> findByOrderIdOrderByIdAsc(Long orderId);

    List<SellerOrderEntity> findBySupplierIdRef(Long supplierIdRef);

    Page<SellerOrderEntity> findBySupplierIdRef(Long supplierIdRef, Pageable pageable);

    Optional<SellerOrderEntity> findByIdAndSupplierIdRef(Long id, Long supplierIdRef);
}
