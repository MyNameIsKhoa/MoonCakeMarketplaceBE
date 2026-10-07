package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> { }
