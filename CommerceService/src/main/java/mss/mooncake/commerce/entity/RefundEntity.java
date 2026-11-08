package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund")
@AttributeOverride(name = "id", column = @Column(name = "refund_id"))
public class RefundEntity extends BaseCommerceEntity {
    @Column(name = "payment_id", nullable = false) private Long paymentId;
    @Column(name = "cancellation_id") private Long cancellationId;
    @Column(name = "dispute_id") private Long disputeId;
    @Column(name = "amount", nullable = false, precision = 18, scale = 2) private BigDecimal amount;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    @Column(name = "status", nullable = false, length = 30) private String status;
    @Column(name = "requested_at", nullable = false) private LocalDateTime requestedAt;
    @Column(name = "refunded_at") private LocalDateTime refundedAt;
}
