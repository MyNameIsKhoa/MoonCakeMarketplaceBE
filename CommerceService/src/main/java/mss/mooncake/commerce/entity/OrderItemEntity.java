package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
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

    public Long getSellerOrderId() { return sellerOrderId; }
    public Long getVariantIdRef() { return variantIdRef; }
    public String getProductCodeSnapshot() { return productCodeSnapshot; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public String getVariantNameSnapshot() { return variantNameSnapshot; }
    public String getImageUrlSnapshot() { return imageUrlSnapshot; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getSubtotal() { return subtotal; }
}
