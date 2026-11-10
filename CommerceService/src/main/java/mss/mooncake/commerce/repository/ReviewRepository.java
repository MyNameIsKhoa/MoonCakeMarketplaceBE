package mss.mooncake.commerce.repository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
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

    @Query(value = """
            WITH sales AS (
                SELECT oi.variant_id_ref AS variant_id, SUM(oi.quantity) AS sold_quantity
                FROM dbo.order_item oi
                JOIN dbo.seller_order so ON so.seller_order_id = oi.seller_order_id
                WHERE oi.variant_id_ref IN (:variantIds)
                  AND so.status IN ('DELIVERED', 'ACCEPTED', 'SETTLED')
                GROUP BY oi.variant_id_ref
            ), ratings AS (
                SELECT oi.variant_id_ref AS variant_id, COUNT_BIG(*) AS review_count,
                       AVG(CAST(r.rating AS DECIMAL(4,2))) AS average_rating
                FROM dbo.review r
                JOIN dbo.order_item oi ON oi.order_item_id = r.order_item_id
                WHERE oi.variant_id_ref IN (:variantIds)
                GROUP BY oi.variant_id_ref
            )
            SELECT v.variant_id AS variantId,
                   COALESCE(s.sold_quantity, 0) AS soldQuantity,
                   COALESCE(r.review_count, 0) AS reviewCount,
                   r.average_rating AS averageRating
            FROM (SELECT variant_id AS variant_id FROM sales UNION SELECT variant_id AS variant_id FROM ratings) v
            LEFT JOIN sales s ON s.variant_id = v.variant_id
            LEFT JOIN ratings r ON r.variant_id = v.variant_id
            """, nativeQuery = true)
    List<ProductSignalProjection> findProductSignals(@Param("variantIds") Collection<Long> variantIds);

    @Query("SELECT r FROM ReviewEntity r, OrderItemEntity oi WHERE r.orderItemId = oi.id AND oi.variantIdRef = :variantId")
    List<ReviewEntity> findByVariantId(@Param("variantId") Long variantId);

    @Query("SELECT r FROM ReviewEntity r, OrderItemEntity oi, SellerOrderEntity so WHERE r.orderItemId = oi.id AND oi.sellerOrderId = so.id AND so.storeIdRef = :storeId")
    List<ReviewEntity> findByStoreId(@Param("storeId") Long storeId);
}
