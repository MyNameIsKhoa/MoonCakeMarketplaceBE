package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CartEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CartRepository extends JpaRepository<CartEntity, Long> { }
