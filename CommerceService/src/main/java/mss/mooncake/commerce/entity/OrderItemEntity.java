package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@lombok.Getter
@lombok.Setter
@Table(name = "order_item")
@AttributeOverride(name = "id", column = @Column(name = "order_item_id"))
public class OrderItemEntity extends BaseCommerceEntity {
    @Column(name = "seller_order_id", nullable = false) private Long sellerOrderId;
    @Column(name = "variant_id_ref") private Long variantIdRef;
    @Column(name = "product_code_snapshot", length = 100) private String productCodeSnapshot;
    @Column(name = "product_name_snapshot", nullable = false, length = 255) private String productNameSnapshot;
    @Column(name = "sku_snapshot", length = 100) private String skuSnapshot;
    @Column(name = "variant_name_snapshot", length = 255) private String variantNameSnapshot;
    @Column(name = "image_url_snapshot", length = 500) private String imageUrlSnapshot;
    @Column(name = "quantity", nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2) private BigDecimal unitPrice;
    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2) private BigDecimal discountAmount;
    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2) private BigDecimal subtotal;
}
