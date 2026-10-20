package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import mss.mooncake.catalogstore.enums.ProductStatus;

@Entity
@Table(name = "product_variant")
@AttributeOverride(name = "id", column = @Column(name = "variant_id"))
public class ProductVariantEntity extends BaseCatalogEntity {
    @Column(name = "product_id", nullable = false) private Long productId;
    @Column(name = "sku", nullable = false, length = 100, unique = true) private String sku;
    @Column(name = "variant_name", nullable = false, length = 255) private String variantName;
    @Column(name = "image_url", length = 500) private String imageUrl;
    @Column(name = "base_price", nullable = false, precision = 18, scale = 2) private BigDecimal basePrice;
    @Column(name = "stock_quantity", nullable = false) private Integer stockQuantity;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) private ProductStatus status;

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getVariantName() { return variantName; }
    public String getImageUrl() { return imageUrl; }
    public BigDecimal getBasePrice() { return basePrice; }
    public Integer getStockQuantity() { return stockQuantity; }
    public ProductStatus getStatus() { return status; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    // productId chỉ set khi tạo; không reparent variant sang product khác.
    public void setProductId(Long productId) { this.productId = productId; }
    public void setSku(String sku) { this.sku = sku; }
    public void setVariantName(String variantName) { this.variantName = variantName; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    public void setStatus(ProductStatus status) { this.status = status; }
}
