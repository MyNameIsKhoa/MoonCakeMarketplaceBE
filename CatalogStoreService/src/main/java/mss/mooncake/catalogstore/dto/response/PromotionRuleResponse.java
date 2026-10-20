package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

public record PromotionRuleResponse(Long promotionRuleId, String ruleType, Long targetVariantId, Long rewardVariantId,
                                    Integer minQuantity, BigDecimal discountPercent, BigDecimal discountAmount,
                                    Integer rewardQuantity) { }
