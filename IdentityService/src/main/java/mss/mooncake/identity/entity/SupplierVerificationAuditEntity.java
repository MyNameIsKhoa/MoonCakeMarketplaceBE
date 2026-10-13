package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "supplier_verification_audit")
@AttributeOverride(name = "id", column = @Column(name = "audit_id"))
public class SupplierVerificationAuditEntity extends BaseIdentityEntity {
    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;
    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;
    @Column(name = "previous_status", length = 30)
    private String previousStatus;
    @Column(name = "new_status", nullable = false, length = 30)
    private String newStatus;
    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;
    @Column(name = "acted_at", nullable = false)
    private LocalDateTime actedAt;

    protected SupplierVerificationAuditEntity() { }

    public SupplierVerificationAuditEntity(Long supplierId, Long actorUserId, String previousStatus,
                                           String newStatus, String reason, LocalDateTime actedAt) {
        this.supplierId = supplierId;
        this.actorUserId = actorUserId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.actedAt = actedAt;
    }

    public Long getSupplierId() { return supplierId; }
    public Long getActorUserId() { return actorUserId; }
    public String getPreviousStatus() { return previousStatus; }
    public String getNewStatus() { return newStatus; }
    public String getReason() { return reason; }
    public LocalDateTime getActedAt() { return actedAt; }
}
