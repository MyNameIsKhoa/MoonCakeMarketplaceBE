package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
public interface SellerOrderRepository extends JpaRepository<SellerOrderEntity, Long> {
    List<SellerOrderEntity> findByOrderIdOrderByIdAsc(Long orderId);
    Page<SellerOrderEntity> findBySupplierIdRef(Long supplierIdRef, Pageable pageable);
    Optional<SellerOrderEntity> findByIdAndSupplierIdRef(Long id, Long supplierIdRef);
}
