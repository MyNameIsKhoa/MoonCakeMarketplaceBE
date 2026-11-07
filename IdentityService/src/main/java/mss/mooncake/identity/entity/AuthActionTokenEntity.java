package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import mss.mooncake.identity.enums.AuthTokenPurpose;

@Entity
@Table(name = "auth_action_token")
@AttributeOverride(name = "id", column = @Column(name = "auth_action_token_id"))
public class AuthActionTokenEntity extends BaseIdentityEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private AuthTokenPurpose purpose;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuthActionTokenEntity() { }

    public AuthActionTokenEntity(Long userId, String tokenHash, AuthTokenPurpose purpose,
                                 LocalDateTime expiresAt, LocalDateTime createdAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public AuthTokenPurpose getPurpose() { return purpose; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
}
