package mss.mooncake.catalogstore.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.enums.PromotionType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PromotionDiscountRulesTest {
    private final PromotionCalculator calculator = new PromotionCalculator();

    @Test
    void sameVariantBuyXGetYUsesGroupsOfXPlusYAndRespectsOtherRuleThresholds() {
        var buy = rule(1L, PromotionType.BUY_X_GET_Y, 1L, 2);
        buy.setRewardVariantId(1L);
        buy.setRewardQuantity(1);
        var fixed = rule(2L, PromotionType.FIXED_DISCOUNT, 1L, 8);
        fixed.setDiscountAmount(new BigDecimal("10"));
        var percent = rule(3L, PromotionType.PERCENT_DISCOUNT, 1L, 8);
        percent.setDiscountPercent(new BigDecimal("10"));

        var seven = calculate(7, List.of(buy, fixed, percent));
        assertThat(seven.discountTotal()).isEqualByComparingTo("200");
        assertThat(seven.appliedPromotions()).extracting(p -> p.promotionRuleId()).containsExactly(1L);

        var eight = calculate(8, List.of(buy, fixed, percent));
        assertThat(eight.discountTotal()).isEqualByComparingTo("269");
        assertThat(eight.totalAmount()).isEqualByComparingTo("531");
        assertThat(eight.appliedPromotions()).extracting(p -> p.promotionRuleId()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void storeWideFixedNeedsTotalQuantityThreshold() {
        var fixed = rule(1L, PromotionType.FIXED_DISCOUNT, null, 2);
        fixed.setDiscountAmount(new BigDecimal("10"));
        assertThat(calculate(1, List.of(fixed)).discountTotal()).isEqualByComparingTo("0");
        assertThat(calculate(2, List.of(fixed)).discountTotal()).isEqualByComparingTo("10");
    }

    private mss.mooncake.catalogstore.dto.response.PromotionCalculationResult calculate(
            int quantity, List<PromotionRuleEntity> rules) {
        var request = new PromotionCalculationRequest(9L,
                List.of(new PromotionCalculationRequest.Line(1L, quantity)));
        var snapshot = new VariantSnapshotResponse(1L, 10L, 9L, 11L, "P", "Cake", "Box", "SKU", null,
                new BigDecimal("100"), 20, "ACTIVE", "ACTIVE", "ACTIVE");
        var promotion = new PromotionEntity();
        ReflectionTestUtils.setField(promotion, "id", 1L);
        promotion.setName("Promotion");
        return calculator.calculate(request, List.of(snapshot), List.of(promotion), rules, LocalDateTime.now());
    }

    private PromotionRuleEntity rule(Long id, PromotionType type, Long target, Integer minimum) {
        var rule = new PromotionRuleEntity();
        ReflectionTestUtils.setField(rule, "id", id);
        rule.setPromotionId(1L);
        rule.setRuleType(type);
        rule.setTargetVariantId(target);
        rule.setMinQuantity(minimum);
        return rule;
    }
}
