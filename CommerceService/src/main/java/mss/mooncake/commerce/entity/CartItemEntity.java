package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "cart_item")
@AttributeOverride(name = "id", column = @Column(name = "cart_item_id"))
public class CartItemEntity extends BaseCommerceEntity {
    @Column(name = "cart_id", nullable = false) private Long cartId;
    @Column(name = "variant_id_ref", nullable = false) private Long variantIdRef;
    @Column(name = "quantity", nullable = false) private Integer quantity;
    @Column(name = "added_at", nullable = false) private LocalDateTime addedAt;
}
