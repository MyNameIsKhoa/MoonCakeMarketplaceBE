package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import mss.mooncake.catalogstore.enums.PromotionType;

/**
 * Field bắt buộc theo ruleType (service kiểm tra):
 * PERCENT_DISCOUNT cần discountPercent; FIXED_DISCOUNT cần discountAmount;
 * BUY_X_GET_Y cần targetVariantId, rewardVariantId, minQuantity,
 * rewardQuantity.
 * target/reward phải thuộc cùng store với promotion.
 */
public record PromotionRuleRequest(
                @NotNull PromotionType ruleType,
                Long targetVariantId,
                Long rewardVariantId,
                @Positive Integer minQuantity,
                @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal discountPercent,
                @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal discountAmount,
                @Positive Integer rewardQuantity) {
}
