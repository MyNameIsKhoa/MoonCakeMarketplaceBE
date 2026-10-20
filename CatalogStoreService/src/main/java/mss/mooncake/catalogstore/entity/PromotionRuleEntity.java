package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import mss.mooncake.catalogstore.enums.PromotionType;

@Entity
@Table(name = "promotion_rule")
@AttributeOverride(name = "id", column = @Column(name = "promotion_rule_id"))
public class PromotionRuleEntity extends BaseCatalogEntity {
    @Column(name = "promotion_id", nullable = false) private Long promotionId;
    @Column(name = "target_variant_id") private Long targetVariantId;
    @Column(name = "reward_variant_id") private Long rewardVariantId;
    // Khớp CK_promotion_rule_type: PERCENT_DISCOUNT, FIXED_DISCOUNT, BUY_X_GET_Y.
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 30) private PromotionType ruleType;
    @Column(name = "min_quantity") private Integer minQuantity;
    @Column(name = "discount_percent", precision = 5, scale = 2) private BigDecimal discountPercent;
    @Column(name = "discount_amount", precision = 18, scale = 2) private BigDecimal discountAmount;
    @Column(name = "reward_quantity") private Integer rewardQuantity;

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getPromotionId() { return promotionId; }
    public Long getTargetVariantId() { return targetVariantId; }
    public Long getRewardVariantId() { return rewardVariantId; }
    public PromotionType getRuleType() { return ruleType; }
    public Integer getMinQuantity() { return minQuantity; }
    public BigDecimal getDiscountPercent() { return discountPercent; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public Integer getRewardQuantity() { return rewardQuantity; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
    public void setTargetVariantId(Long targetVariantId) { this.targetVariantId = targetVariantId; }
    public void setRewardVariantId(Long rewardVariantId) { this.rewardVariantId = rewardVariantId; }
    public void setRuleType(PromotionType ruleType) { this.ruleType = ruleType; }
    public void setMinQuantity(Integer minQuantity) { this.minQuantity = minQuantity; }
    public void setDiscountPercent(BigDecimal discountPercent) { this.discountPercent = discountPercent; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public void setRewardQuantity(Integer rewardQuantity) { this.rewardQuantity = rewardQuantity; }
}
