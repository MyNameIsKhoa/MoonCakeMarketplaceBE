package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import mss.mooncake.commerce.enums.SettlementStatus;

@Entity
@Table(name = "settlement")
@AttributeOverride(name = "id", column = @Column(name = "settlement_id"))
public class SettlementEntity extends BaseCommerceEntity {
    @Column(name = "seller_order_id", nullable = false, unique = true) private Long sellerOrderId;
    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 2) private BigDecimal grossAmount;
    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 2) private BigDecimal feeRate;
    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 2) private BigDecimal feeAmount;
    @Column(name = "supplier_payout_amount", nullable = false, precision = 18, scale = 2) private BigDecimal supplierPayoutAmount;
    @Column(name = "status", nullable = false, length = 30) private SettlementStatus status;
    @Column(name = "settled_at") private LocalDateTime settledAt;
}
