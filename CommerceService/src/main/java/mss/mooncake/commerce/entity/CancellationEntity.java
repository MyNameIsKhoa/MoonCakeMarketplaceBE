package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cancellation")
@AttributeOverride(name = "id", column = @Column(name = "cancellation_id"))
public class CancellationEntity extends BaseCommerceEntity {
    // One REQUESTED/APPROVED case per seller order (filtered unique index, V11); rejected cases stay as history.
    @Column(name = "seller_order_id", nullable = false) private Long sellerOrderId;
    @Column(name = "actor_type", nullable = false, length = 30) private String actorType;
    @Column(name = "actor_id_ref", nullable = false) private Long actorIdRef;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    @Column(name = "refund_percent", precision = 5, scale = 2) private BigDecimal refundPercent;
    @Column(name = "status", nullable = false, length = 30) private String status;
    @Column(name = "cancelled_at") private LocalDateTime cancelledAt;
}
