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
    @Column(name = "order_id", nullable = false) private Long orderId;
    @Column(name = "seller_order_no", nullable = false, length = 100, unique = true) private String sellerOrderNo;
    @Column(name = "store_id_ref", nullable = false) private Long storeIdRef;
    @Column(name = "supplier_id_ref", nullable = false) private Long supplierIdRef;
    @Column(name = "quotation_id_ref") private Long quotationIdRef;
    @Column(name = "store_name_snapshot", nullable = false, length = 255) private String storeNameSnapshot;
    @Column(name = "supplier_name_snapshot", nullable = false, length = 255) private String supplierNameSnapshot;
    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2) private BigDecimal subtotal;
    @Column(name = "shipping_fee", nullable = false, precision = 18, scale = 2) private BigDecimal shippingFee;
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2) private BigDecimal totalAmount;
    @Column(name = "deposit_percent", precision = 5, scale = 2) private BigDecimal depositPercent;
    @Column(name = "deposit_amount", precision = 18, scale = 2) private BigDecimal depositAmount;
    @Column(name = "remaining_amount", precision = 18, scale = 2) private BigDecimal remainingAmount;
    @Column(name = "remaining_due_at") private LocalDateTime remainingDueAt;
    @Column(name = "delivery_date_snapshot") private LocalDate deliveryDateSnapshot;
    @Column(name = "shipping_method_snapshot", length = 100) private String shippingMethodSnapshot;
    @Column(name = "payment_terms_snapshot", columnDefinition = "NVARCHAR(MAX)") private String paymentTermsSnapshot;
    @Column(name = "status", nullable = false, length = 30) private String status;
}
