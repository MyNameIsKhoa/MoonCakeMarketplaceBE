package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import mss.mooncake.catalogstore.enums.CategoryStatus;

@Entity
@Table(name = "category")
@AttributeOverride(name = "id", column = @Column(name = "category_id"))
public class CategoryEntity extends BaseCatalogEntity {

    @Column(name = "parent_category_id")
    private Long parentCategoryId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255, unique = true)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private CategoryStatus status;

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getParentCategoryId() { return parentCategoryId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public CategoryStatus getStatus() { return status; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    public void setParentCategoryId(Long parentCategoryId) { this.parentCategoryId = parentCategoryId; }
    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setStatus(CategoryStatus status) { this.status = status; }
}
