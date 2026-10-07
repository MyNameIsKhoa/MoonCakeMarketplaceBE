package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
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
}
