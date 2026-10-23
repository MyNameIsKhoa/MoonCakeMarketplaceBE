package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import mss.mooncake.catalogstore.enums.StoreStatus;

@Entity
@Table(name = "store")
@AttributeOverride(name = "id", column = @Column(name = "store_id"))
public class StoreEntity extends BaseCatalogEntity {

    @Column(name = "supplier_id_ref", nullable = false)
    private Long supplierIdRef;

    @Column(name = "store_code", nullable = false, length = 100, unique = true)
    private String storeCode;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", nullable = false, length = 255, unique = true)
    private String slug;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "banner_url", length = 500)
    private String bannerUrl;

    @Column(name = "theme_key", length = 100)
    private String themeKey;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StoreStatus status;

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getSupplierIdRef() { return supplierIdRef; }
    public String getStoreCode() { return storeCode; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getLogoUrl() { return logoUrl; }
    public String getBannerUrl() { return bannerUrl; }
    public String getThemeKey() { return themeKey; }
    public String getDescription() { return description; }
    public StoreStatus getStatus() { return status; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    public void setSupplierIdRef(Long supplierIdRef) { this.supplierIdRef = supplierIdRef; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }
    public void setName(String name) { this.name = name; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public void setThemeKey(String themeKey) { this.themeKey = themeKey; }
    public void setDescription(String description) { this.description = description; }
    public void setStatus(StoreStatus status) { this.status = status; }
}

