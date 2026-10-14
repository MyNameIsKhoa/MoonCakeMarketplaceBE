package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_order")
@AttributeOverride(name = "id", column = @Column(name = "order_id"))
public class CustomerOrderEntity extends BaseCommerceEntity {

    @Column(name = "order_no", nullable = false, length = 100, unique = true)
    private String orderNo;

    @Column(name = "customer_id_ref", nullable = false)
    private Long customerIdRef;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "rfq_id_ref")
    private Long rfqIdRef;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal discountTotal;

    @Column(name = "shipping_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal shippingTotal;

    @Column(name = "grand_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "placed_at", nullable = false)
    private LocalDateTime placedAt;

    public CustomerOrderEntity() {}

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getCustomerIdRef() { return customerIdRef; }
    public void setCustomerIdRef(Long customerIdRef) { this.customerIdRef = customerIdRef; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public Long getRfqIdRef() { return rfqIdRef; }
    public void setRfqIdRef(Long rfqIdRef) { this.rfqIdRef = rfqIdRef; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountTotal() { return discountTotal; }
    public void setDiscountTotal(BigDecimal discountTotal) { this.discountTotal = discountTotal; }

    public BigDecimal getShippingTotal() { return shippingTotal; }
    public void setShippingTotal(BigDecimal shippingTotal) { this.shippingTotal = shippingTotal; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getPlacedAt() { return placedAt; }
    public void setPlacedAt(LocalDateTime placedAt) { this.placedAt = placedAt; }
}
