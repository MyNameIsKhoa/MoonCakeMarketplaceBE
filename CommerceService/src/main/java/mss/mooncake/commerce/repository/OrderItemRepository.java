package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {
    List<OrderItemEntity> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);
}
