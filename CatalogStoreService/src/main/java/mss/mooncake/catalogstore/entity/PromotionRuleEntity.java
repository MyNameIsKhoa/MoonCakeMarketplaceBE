package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "promotion_rule")
@AttributeOverride(name = "id", column = @Column(name = "promotion_rule_id"))
public class PromotionRuleEntity extends BaseCatalogEntity {
    @Column(name = "promotion_id", nullable = false) private Long promotionId;
    @Column(name = "target_variant_id") private Long targetVariantId;
    @Column(name = "reward_variant_id") private Long rewardVariantId;
    @Column(name = "rule_type", nullable = false, length = 30) private String ruleType;
    @Column(name = "min_quantity") private Integer minQuantity;
    @Column(name = "discount_percent", precision = 5, scale = 2) private BigDecimal discountPercent;
    @Column(name = "discount_amount", precision = 18, scale = 2) private BigDecimal discountAmount;
    @Column(name = "reward_quantity") private Integer rewardQuantity;
}
