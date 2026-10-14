package mss.mooncake.commerce.repository;

import java.util.List;
import java.util.Optional;
import mss.mooncake.commerce.entity.ReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<ReviewEntity, Long> {

    boolean existsByOrderItemId(Long orderItemId);

    Optional<ReviewEntity> findByOrderItemId(Long orderItemId);

    List<ReviewEntity> findByCustomerIdRef(Long customerIdRef);

    @Query("SELECT r FROM ReviewEntity r, OrderItemEntity oi WHERE r.orderItemId = oi.id AND oi.variantIdRef = :variantId")
    List<ReviewEntity> findByVariantId(@Param("variantId") Long variantId);
}
