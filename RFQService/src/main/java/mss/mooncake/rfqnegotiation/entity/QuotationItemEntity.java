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
    public static final String CUSTOM_ORDER_IMAGE_URL = "/images/placeholders/custom-order.svg";
    @Column(name = "quotation_id", nullable = false) private Long quotationId;
    @Column(name = "variant_id_ref") private Long variantIdRef;
    @Column(name = "item_name_snapshot", nullable = false, length = 255) private String itemNameSnapshot;
    @Column(name = "sku_snapshot", length = 100) private String skuSnapshot;
    @Column(name = "image_url_snapshot", length = 500) private String imageUrlSnapshot;
    @Column(name = "quantity", nullable = false) private Integer quantity;
    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2) private BigDecimal unitPrice;
    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2) private BigDecimal discountAmount;
    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2) private BigDecimal subtotal;
    @Column(name = "customization_note", columnDefinition = "NVARCHAR(MAX)") private String customizationNote;

    public Long getQuotationId() { return quotationId; }
    public void setQuotationId(Long quotationId) { this.quotationId = quotationId; }
    public Long getVariantIdRef() { return variantIdRef; }
    public void setVariantIdRef(Long variantIdRef) { this.variantIdRef = variantIdRef; }
    public String getItemNameSnapshot() { return itemNameSnapshot; }
    public void setItemNameSnapshot(String itemNameSnapshot) { this.itemNameSnapshot = itemNameSnapshot; }
    public String getSkuSnapshot() { return skuSnapshot; }
    public void setSkuSnapshot(String skuSnapshot) { this.skuSnapshot = skuSnapshot; }
    public String getImageUrlSnapshot() { return imageUrlSnapshot; }
    public void setImageUrlSnapshot(String imageUrlSnapshot) { this.imageUrlSnapshot = imageUrlSnapshot; }
    public String orderImageUrl() {
        return imageUrlSnapshot == null && variantIdRef == null ? CUSTOM_ORDER_IMAGE_URL : imageUrlSnapshot;
    }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public String getCustomizationNote() { return customizationNote; }
    public void setCustomizationNote(String customizationNote) { this.customizationNote = customizationNote; }
}
