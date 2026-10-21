package mss.mooncake.commerce.repository;
import mss.mooncake.commerce.entity.CartItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface CartItemRepository extends JpaRepository<CartItemEntity, Long> {
    List<CartItemEntity> findByCartIdOrderByIdAsc(Long cartId);
    Optional<CartItemEntity> findByIdAndCartId(Long id, Long cartId);
    Optional<CartItemEntity> findByCartIdAndVariantIdRef(Long cartId, Long variantIdRef);
    void deleteByCartId(Long cartId);
}
