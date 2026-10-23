package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import mss.mooncake.catalogstore.enums.StoreBranchStatus;

@Entity
@Table(name = "store_branch")
@AttributeOverride(name = "id", column = @Column(name = "store_branch_id"))
public class StoreBranchEntity extends BaseCatalogEntity {

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address_line", nullable = false, length = 500)
    private String addressLine;

    @Column(name = "ward", length = 100)
    private String ward;

    @Column(name = "district", length = 100)
    private String district;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "opening_hours", length = 255)
    private String openingHours;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StoreBranchStatus status;

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getStoreId() { return storeId; }
    public String getName() { return name; }
    public String getAddressLine() { return addressLine; }
    public String getWard() { return ward; }
    public String getDistrict() { return district; }
    public String getCity() { return city; }
    public String getPhone() { return phone; }
    public String getOpeningHours() { return openingHours; }
    public boolean isPrimary() { return primary; }
    public StoreBranchStatus getStatus() { return status; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public void setName(String name) { this.name = name; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
    public void setWard(String ward) { this.ward = ward; }
    public void setDistrict(String district) { this.district = district; }
    public void setCity(String city) { this.city = city; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setOpeningHours(String openingHours) { this.openingHours = openingHours; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public void setStatus(StoreBranchStatus status) { this.status = status; }
}
