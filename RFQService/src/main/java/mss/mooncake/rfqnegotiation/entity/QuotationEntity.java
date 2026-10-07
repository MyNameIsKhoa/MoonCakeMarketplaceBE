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
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;

@Entity
@Table(name = "quotation")
@AttributeOverride(name = "id", column = @Column(name = "quotation_id"))
public class QuotationEntity extends BaseRfqEntity {
    @Column(name = "rfq_id", nullable = false) private Long rfqId;
    @Column(name = "supplier_id_ref", nullable = false) private Long supplierIdRef;
    @Column(name = "store_id_ref", nullable = false) private Long storeIdRef;
    @Column(name = "previous_quotation_id") private Long previousQuotationId;
    @Column(name = "version_no", nullable = false) private Integer versionNo;
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2) private BigDecimal totalAmount;
    @Column(name = "deposit_percent", nullable = false, precision = 5, scale = 2) private BigDecimal depositPercent;
    @Column(name = "valid_until", nullable = false) private LocalDateTime validUntil;
    @Column(name = "lead_time_days") private Integer leadTimeDays;
    @Column(name = "proposed_delivery_date") private LocalDate proposedDeliveryDate;
    @Column(name = "shipping_method", length = 100) private String shippingMethod;
    @Column(name = "shipping_fee", nullable = false, precision = 18, scale = 2) private BigDecimal shippingFee;
    @Column(name = "payment_terms", columnDefinition = "NVARCHAR(MAX)") private String paymentTerms;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30) private QuotationStatus status;
    @Column(name = "submitted_at") private LocalDateTime submittedAt;
    @Column(name = "accepted_at") private LocalDateTime acceptedAt;
}
