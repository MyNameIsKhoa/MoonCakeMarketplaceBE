package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "category")
@AttributeOverride(name = "id", column = @Column(name = "category_id"))
public class CategoryEntity extends BaseCatalogEntity {
    @Column(name = "parent_category_id") private Long parentCategoryId;
    @Column(name = "name", nullable = false, length = 255) private String name;
    @Column(name = "slug", nullable = false, length = 255, unique = true) private String slug;
    @Column(name = "status", nullable = false, length = 30) private String status;
}
