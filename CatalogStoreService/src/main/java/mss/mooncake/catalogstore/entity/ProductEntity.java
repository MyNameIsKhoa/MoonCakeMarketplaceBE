package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import mss.mooncake.catalogstore.enums.ProductStatus;

@Entity
@Table(name = "product")
@AttributeOverride(name = "id", column = @Column(name = "product_id"))
public class ProductEntity extends BaseCatalogEntity {
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(name = "category_id", nullable = false) private Long categoryId;
    @Column(name = "product_code", nullable = false, length = 100) private String productCode;
    @Column(name = "name", nullable = false, length = 255) private String name;
    @Column(name = "description", columnDefinition = "NVARCHAR(MAX)") private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) private ProductStatus status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    @PrePersist
    private void onCreate() {
        createdAt = LocalDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getStoreId() { return storeId; }
    public Long getCategoryId() { return categoryId; }
    public String getProductCode() { return productCode; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ProductStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    // storeId chỉ set khi tạo; không reparent product sang store khác.
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setStatus(ProductStatus status) { this.status = status; }
}
