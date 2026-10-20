package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * IN08: kết quả tính khuyến mãi cho một store. Tiền VND làm tròn HALF_UP tới đơn vị;
 * mỗi line nhận tối đa một rule (rule có lợi nhất cho khách); discountTotal không vượt subtotal.
 * Các giả định này cần TV3 review trước khi Commerce tích hợp.
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
                             Long promotionId, Long promotionRuleId) { }

    /** rewardVariantId/rewardQuantity chỉ có với BUY_X_GET_Y. */
    public record AppliedPromotion(Long promotionId, String promotionName, Long promotionRuleId, String ruleType,
                                   BigDecimal discountAmount, Long rewardVariantId, Integer rewardQuantity) { }
}
