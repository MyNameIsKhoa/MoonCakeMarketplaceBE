package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_profile")
@AttributeOverride(name = "id", column = @Column(name = "supplier_id"))
public class SupplierProfileEntity extends BaseIdentityEntity {
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;
    @Column(name = "company_name", nullable = false, length = 255)
    private String companyName;
    @Column(name = "tax_code", nullable = false, length = 100, unique = true)
    private String taxCode;
    @Column(name = "verification_status", nullable = false, length = 30)
    private String verificationStatus;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
