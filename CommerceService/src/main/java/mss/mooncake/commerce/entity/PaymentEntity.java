package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import mss.mooncake.commerce.enums.PaymentStatus;
import mss.mooncake.commerce.enums.PaymentType;

@Entity
@Table(name = "payment")
@AttributeOverride(name = "id", column = @Column(name = "payment_id"))
public class PaymentEntity extends BaseCommerceEntity {
    @Column(name = "seller_order_id", nullable = false) private Long sellerOrderId;
    @Enumerated(EnumType.STRING) @Column(name = "payment_type", nullable = false, length = 30) private PaymentType paymentType;
    @Column(name = "amount", nullable = false, precision = 18, scale = 2) private BigDecimal amount;
    @Column(name = "due_at") private LocalDateTime dueAt;
    @Column(name = "provider", length = 100) private String provider;
    @Column(name = "transaction_ref", length = 255) private String transactionRef;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30) private PaymentStatus status;
    @Column(name = "paid_at") private LocalDateTime paidAt;
}
