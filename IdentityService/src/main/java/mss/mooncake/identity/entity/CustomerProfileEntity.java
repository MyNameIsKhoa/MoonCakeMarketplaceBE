package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_profile")
@AttributeOverride(name = "id", column = @Column(name = "customer_id"))
public class CustomerProfileEntity extends BaseIdentityEntity {
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;
    @Column(name = "phone", length = 30)
    private String phone;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
