package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_item")
@AttributeOverride(name = "id", column = @Column(name = "quotation_item_id"))
public class QuotationItemEntity extends BaseRfqEntity {
    @Column(name = "quotation_id", nullable = false) private Long quotationId;
    @Column(name = "variant_id_ref") private Long variantIdRef;
    @Column(name = "item_name_snapshot", nullable = false, length = 255) private String itemNameSnapshot;
    @Column(name = "sku_snapshot", length = 100) private String skuSnapshot;
    @Column(name = "quantity", nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2) private BigDecimal unitPrice;
    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2) private BigDecimal discountAmount;
    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2) private BigDecimal subtotal;
    @Column(name = "customization_note", columnDefinition = "NVARCHAR(MAX)") private String customizationNote;
}
