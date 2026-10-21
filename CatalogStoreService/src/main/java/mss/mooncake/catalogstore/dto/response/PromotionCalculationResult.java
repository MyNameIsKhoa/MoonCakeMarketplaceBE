package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * IN08: contract kết quả tính khuyến mãi cho một store. Quy tắc đã chốt:
 * cộng dồn khuyến mãi, mỗi reward line chọn một BUY_X_GET_Y tốt nhất,
 * giảm tiền trước giảm phần trăm, làm tròn lên tại tổng cuối seller order.
 * Fixed discount toàn store phân bổ theo tỷ lệ line subtotal.
 */
public record PromotionCalculationResult(
        Long storeId,
        LocalDateTime calculatedAt,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal totalAmount,
        List<LineResult> lines,
        List<AppliedPromotion> appliedPromotions) {

    /** discountAmount là tổng giảm của cả line, không phải mỗi unit. */
    public record LineResult(Long variantId, Integer quantity, BigDecimal unitPrice, BigDecimal lineSubtotal,
                             BigDecimal discountAmount, BigDecimal lineTotal,
                             List<RuleApplication> appliedRules) { }

    public record RuleApplication(Long promotionId, Long promotionRuleId, BigDecimal discountAmount) { }

    /** rewardVariantId/rewardQuantity chỉ có với BUY_X_GET_Y. */
    public record AppliedPromotion(Long promotionId, String promotionName, Long promotionRuleId, String ruleType,
                                   BigDecimal discountAmount, Long rewardVariantId, Integer rewardQuantity) { }
}
