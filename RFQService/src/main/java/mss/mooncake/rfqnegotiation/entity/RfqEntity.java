package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "rfq")
@AttributeOverride(name = "id", column = @Column(name = "rfq_id"))
public class RfqEntity extends BaseRfqEntity {
    @Column(name = "rfq_code", nullable = false, length = 100, unique = true) private String rfqCode;
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    @Column(name = "title", nullable = false, length = 255) private String title;
    @Column(name = "description", columnDefinition = "NVARCHAR(MAX)") private String description;
    @Column(name = "quantity", nullable = false) private Integer quantity;
    @Column(name = "budget_amount", precision = 18, scale = 2) private BigDecimal budgetAmount;
    @Column(name = "requested_delivery_date") private LocalDate requestedDeliveryDate;
    @Column(name = "delivery_location", length = 500) private String deliveryLocation;
    @Column(name = "customization_requirements", columnDefinition = "NVARCHAR(MAX)") private String customizationRequirements;
    @Column(name = "status", nullable = false, length = 30) private String status;
    @Column(name = "expires_at") private LocalDateTime expiresAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
