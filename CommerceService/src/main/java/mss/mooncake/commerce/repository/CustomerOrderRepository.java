package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CustomerOrderRepository extends JpaRepository<CustomerOrderEntity, Long> { }
