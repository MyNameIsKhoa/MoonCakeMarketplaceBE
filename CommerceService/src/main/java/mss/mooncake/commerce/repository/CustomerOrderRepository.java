package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
public interface CustomerOrderRepository extends JpaRepository<CustomerOrderEntity, Long> {
    Page<CustomerOrderEntity> findByCustomerIdRef(Long customerIdRef, Pageable pageable);
    Optional<CustomerOrderEntity> findByIdAndCustomerIdRef(Long id, Long customerIdRef);
}
