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
    @Column(name = "decision_reason", length = 2000) private String decisionReason;

    public Long getRfqId() { return rfqId; }
    public void setRfqId(Long rfqId) { this.rfqId = rfqId; }
    public Long getSupplierIdRef() { return supplierIdRef; }
    public void setSupplierIdRef(Long supplierIdRef) { this.supplierIdRef = supplierIdRef; }
    public Long getStoreIdRef() { return storeIdRef; }
    public void setStoreIdRef(Long storeIdRef) { this.storeIdRef = storeIdRef; }
    public Long getPreviousQuotationId() { return previousQuotationId; }
    public void setPreviousQuotationId(Long previousQuotationId) { this.previousQuotationId = previousQuotationId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getDepositPercent() { return depositPercent; }
    public void setDepositPercent(BigDecimal depositPercent) { this.depositPercent = depositPercent; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public Integer getLeadTimeDays() { return leadTimeDays; }
    public void setLeadTimeDays(Integer leadTimeDays) { this.leadTimeDays = leadTimeDays; }
    public LocalDate getProposedDeliveryDate() { return proposedDeliveryDate; }
    public void setProposedDeliveryDate(LocalDate proposedDeliveryDate) { this.proposedDeliveryDate = proposedDeliveryDate; }
    public String getShippingMethod() { return shippingMethod; }
    public void setShippingMethod(String shippingMethod) { this.shippingMethod = shippingMethod; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal shippingFee) { this.shippingFee = shippingFee; }
    public String getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(String paymentTerms) { this.paymentTerms = paymentTerms; }
    public QuotationStatus getStatus() { return status; }
    public void setStatus(QuotationStatus status) { this.status = status; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
}
