package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@lombok.Getter
@lombok.Setter
@Table(name = "seller_order")
@AttributeOverride(name = "id", column = @Column(name = "seller_order_id"))
public class SellerOrderEntity extends BaseCommerceEntity {
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "seller_order_no", nullable = false, length = 100, unique = true)
    private String sellerOrderNo;

    @Column(name = "store_id_ref", nullable = false)
    private Long storeIdRef;

    @Column(name = "supplier_id_ref", nullable = false)
    private Long supplierIdRef;

    @Column(name = "quotation_id_ref")
    private Long quotationIdRef;

    @Column(name = "store_name_snapshot", nullable = false, length = 255)
    private String storeNameSnapshot;

    @Column(name = "supplier_name_snapshot", nullable = false, length = 255)
    private String supplierNameSnapshot;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "shipping_fee", nullable = false, precision = 18, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "deposit_percent", precision = 5, scale = 2)
    private BigDecimal depositPercent;

    @Column(name = "deposit_amount", precision = 18, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "remaining_amount", precision = 18, scale = 2)
    private BigDecimal remainingAmount;

    @Column(name = "remaining_due_at")
    private LocalDateTime remainingDueAt;

    @Column(name = "delivery_date_snapshot")
    private LocalDate deliveryDateSnapshot;

    @Column(name = "shipping_method_snapshot", length = 100)
    private String shippingMethodSnapshot;

    @Column(name = "payment_terms_snapshot", columnDefinition = "NVARCHAR(MAX)")
    private String paymentTermsSnapshot;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    public SellerOrderEntity() {}

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getSellerOrderNo() { return sellerOrderNo; }
    public void setSellerOrderNo(String sellerOrderNo) { this.sellerOrderNo = sellerOrderNo; }

    public Long getStoreIdRef() { return storeIdRef; }
    public void setStoreIdRef(Long storeIdRef) { this.storeIdRef = storeIdRef; }

    public Long getSupplierIdRef() { return supplierIdRef; }
    public void setSupplierIdRef(Long supplierIdRef) { this.supplierIdRef = supplierIdRef; }

    public Long getQuotationIdRef() { return quotationIdRef; }
    public void setQuotationIdRef(Long quotationIdRef) { this.quotationIdRef = quotationIdRef; }

    public String getStoreNameSnapshot() { return storeNameSnapshot; }
    public void setStoreNameSnapshot(String storeNameSnapshot) { this.storeNameSnapshot = storeNameSnapshot; }

    public String getSupplierNameSnapshot() { return supplierNameSnapshot; }
    public void setSupplierNameSnapshot(String supplierNameSnapshot) { this.supplierNameSnapshot = supplierNameSnapshot; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal shippingFee) { this.shippingFee = shippingFee; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getDepositPercent() { return depositPercent; }
    public void setDepositPercent(BigDecimal depositPercent) { this.depositPercent = depositPercent; }

    public BigDecimal getDepositAmount() { return depositAmount; }
    public void setDepositAmount(BigDecimal depositAmount) { this.depositAmount = depositAmount; }

    public BigDecimal getRemainingAmount() { return remainingAmount; }
    public void setRemainingAmount(BigDecimal remainingAmount) { this.remainingAmount = remainingAmount; }

    public LocalDateTime getRemainingDueAt() { return remainingDueAt; }
    public void setRemainingDueAt(LocalDateTime remainingDueAt) { this.remainingDueAt = remainingDueAt; }

    public LocalDate getDeliveryDateSnapshot() { return deliveryDateSnapshot; }
    public void setDeliveryDateSnapshot(LocalDate deliveryDateSnapshot) { this.deliveryDateSnapshot = deliveryDateSnapshot; }

    public String getShippingMethodSnapshot() { return shippingMethodSnapshot; }
    public void setShippingMethodSnapshot(String shippingMethodSnapshot) { this.shippingMethodSnapshot = shippingMethodSnapshot; }

    public String getPaymentTermsSnapshot() { return paymentTermsSnapshot; }
    public void setPaymentTermsSnapshot(String paymentTermsSnapshot) { this.paymentTermsSnapshot = paymentTermsSnapshot; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
