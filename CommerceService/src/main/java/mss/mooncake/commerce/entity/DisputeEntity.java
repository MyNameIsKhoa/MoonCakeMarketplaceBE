package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import mss.mooncake.commerce.enums.DisputeStatus;

@Entity
@Table(name = "dispute")
@AttributeOverride(name = "id", column = @Column(name = "dispute_id"))
public class DisputeEntity extends BaseCommerceEntity {
    @Column(name = "seller_order_id", nullable = false) private Long sellerOrderId;
    @Column(name = "opened_by_user_id_ref", nullable = false) private Long openedByUserIdRef;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    @Column(name = "evidence_url", length = 1000) private String evidenceUrl;
    @Column(name = "status", nullable = false, length = 30) private DisputeStatus status;
    @Column(name = "resolution", columnDefinition = "NVARCHAR(MAX)") private String resolution;
    @Column(name = "opened_at", nullable = false) private LocalDateTime openedAt;
    @Column(name = "resolved_at") private LocalDateTime resolvedAt;
}
