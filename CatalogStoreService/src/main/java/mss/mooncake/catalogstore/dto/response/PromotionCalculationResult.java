package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

public record PromotionCalculationResult(BigDecimal discountAmount, BigDecimal totalAmount,
                                         String promotionId, String ruleType) { }
