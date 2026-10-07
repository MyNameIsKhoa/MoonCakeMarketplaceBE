package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import mss.mooncake.identity.enums.UserStatus;

@Entity
@Table(name = "app_user")
@AttributeOverride(name = "id", column = @Column(name = "user_id"))
public class UserEntity extends BaseIdentityEntity {
    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
