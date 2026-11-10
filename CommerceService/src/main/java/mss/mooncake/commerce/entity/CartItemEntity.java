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

    @Column(name = "selected", nullable = false) private boolean selected;

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public Long getCartId() { return cartId; }
    public void setCartId(Long cartId) { this.cartId = cartId; }
    public Long getVariantIdRef() { return variantIdRef; }
    public void setVariantIdRef(Long variantIdRef) { this.variantIdRef = variantIdRef; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}
