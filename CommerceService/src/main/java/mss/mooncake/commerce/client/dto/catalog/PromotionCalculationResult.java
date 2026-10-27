package mss.mooncake.commerce.client.dto.catalog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PromotionCalculationResult(Long storeId, LocalDateTime calculatedAt, BigDecimal subtotal,
                                         BigDecimal discountTotal, BigDecimal totalAmount,
                                         List<LineResult> lines, List<AppliedPromotion> appliedPromotions) {
    public record LineResult(Long variantId, Integer quantity, BigDecimal unitPrice, BigDecimal lineSubtotal,
                             BigDecimal discountAmount, BigDecimal lineTotal,
                             List<RuleApplication> appliedRules) { }

    public record RuleApplication(Long promotionId, Long promotionRuleId, BigDecimal discountAmount) { }

    public record AppliedPromotion(Long promotionId, String promotionName, Long promotionRuleId,
                                   String ruleType, BigDecimal discountAmount,
                                   Long rewardVariantId, Integer rewardQuantity) { }
}
