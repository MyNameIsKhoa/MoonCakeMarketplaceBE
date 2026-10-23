package mss.mooncake.commerce.repository;

import java.util.Collection;
import java.util.List;
import mss.mooncake.commerce.entity.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    List<OrderItemEntity> findBySellerOrderId(Long sellerOrderId);

    List<OrderItemEntity> findBySellerOrderIdIn(Collection<Long> sellerOrderIds);
}
