package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CartItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CartItemRepository extends JpaRepository<CartItemEntity, Long> { }
