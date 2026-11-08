package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;

@Getter
@Setter
@Entity
@Table(name = "rfq")
@AttributeOverride(name = "id", column = @Column(name = "rfq_id"))
public class RfqEntity extends BaseRfqEntity {
    @Column(name = "rfq_code", nullable = false, length = 100, unique = true) private String rfqCode;
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    /** Buyer's name at creation, shown to suppliers in conversations; null for older RFQs. */
    @Column(name = "customer_name_snapshot", length = 255) private String customerNameSnapshot;
    @Column(name = "title", nullable = false, length = 255) private String title;
    @Column(name = "description", columnDefinition = "NVARCHAR(MAX)") private String description;
    @Column(name = "quantity", nullable = false) private Integer quantity;
    @Column(name = "budget_amount", precision = 18, scale = 2) private BigDecimal budgetAmount;
    @Column(name = "requested_delivery_date") private LocalDate requestedDeliveryDate;
    @Column(name = "delivery_location", length = 500) private String deliveryLocation;
    @Column(name = "customization_requirements", columnDefinition = "NVARCHAR(MAX)") private String customizationRequirements;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) private RfqStatus status;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20) private RfqAudience audience = RfqAudience.ALL;
    /** CLOSED by the system because accepted quotations reached the quantity; reopened if one of their orders is cancelled. */
    @Column(name = "auto_closed", nullable = false) private boolean autoClosed;
}
