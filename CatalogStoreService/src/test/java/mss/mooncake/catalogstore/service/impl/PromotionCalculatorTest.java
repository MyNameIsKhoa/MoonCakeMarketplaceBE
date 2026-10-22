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

class PromotionCalculatorTest {
    private final PromotionCalculator calculator = new PromotionCalculator();

    @Test
    void bestRewardThenFixedThenSummedPercentages() {
        var basket = basket(new PromotionCalculationRequest.Line(1L, 2),
                new PromotionCalculationRequest.Line(2L, 2));
        var results = calculator.calculate(basket, List.of(snapshot(1L, "100.00"), snapshot(2L, "40.00")),
                List.of(promotion(1L), promotion(2L)),
                List.of(buy(1L, 1L, 1L, 2L, 2, 1), buy(2L, 1L, 1L, 2L, 2, 2),
                        fixed(3L, 1L, null, "30.00"), percent(4L, 2L, null, "10.00"),
                        percent(5L, 2L, null, "20.00")), LocalDateTime.now());
        assertThat(results.subtotal()).isEqualByComparingTo("280.00");
        assertThat(results.discountTotal()).isEqualByComparingTo("161.00");
        assertThat(results.totalAmount()).isEqualByComparingTo("119");
        assertThat(results.lines().get(0).lineTotal()).isEqualByComparingTo("119.00");
        assertThat(results.lines().get(1).lineTotal()).isEqualByComparingTo("0.00");
        assertThat(results.appliedPromotions()).extracting(p -> p.promotionRuleId())
                .contains(2L, 3L, 4L, 5L).doesNotContain(1L);
        assertThat(results.appliedPromotions().stream().map(p -> p.discountAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(results.discountTotal());
    }

    @Test
    void storeFixedIsProportionalAndRemainderGoesToLastLine() {
        var result = calculator.calculate(basket(new PromotionCalculationRequest.Line(1L, 1),
                        new PromotionCalculationRequest.Line(2L, 1)),
                List.of(snapshot(1L, "100.00"), snapshot(2L, "50.00")), List.of(promotion(1L)),
                List.of(fixed(1L, 1L, null, "10.00")), LocalDateTime.now());
        assertThat(result.lines().get(0).discountAmount()).isEqualByComparingTo("6.66");
        assertThat(result.lines().get(1).discountAmount()).isEqualByComparingTo("3.34");
        assertThat(result.totalAmount()).isEqualByComparingTo("140");
    }

    @Test
    void percentagesAreCappedAndOnlySellerTotalRoundsUp() {
        var result = calculator.calculate(basket(new PromotionCalculationRequest.Line(1L, 1)),
                List.of(snapshot(1L, "0.60")), List.of(promotion(1L)),
                List.of(percent(1L, 1L, null, "25.00"), percent(2L, 1L, null, "25.00")),
                LocalDateTime.now());
        assertThat(result.lines().getFirst().lineTotal()).isEqualByComparingTo("0.3000");
        assertThat(result.totalAmount()).isEqualByComparingTo("1");
        var capped = calculator.calculate(basket(new PromotionCalculationRequest.Line(1L, 1)),
                List.of(snapshot(1L, "100.00")), List.of(promotion(1L)),
                List.of(percent(1L, 1L, null, "80"), percent(2L, 1L, null, "80")),
                LocalDateTime.now());
        assertThat(capped.discountTotal()).isEqualByComparingTo("100.00");
        assertThat(capped.totalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void buyXGetYCountsMultipleTriggersAndNeedsRewardInBasket() {
        var promotion = promotion(1L);
        var rule = buy(1L, 1L, 1L, 2L, 2, 1);
        var result = calculator.calculate(basket(new PromotionCalculationRequest.Line(1L, 4),
                        new PromotionCalculationRequest.Line(2L, 3)),
                List.of(snapshot(1L, "10.00"), snapshot(2L, "5.00")),
                List.of(promotion), List.of(rule), LocalDateTime.now());
        assertThat(result.discountTotal()).isEqualByComparingTo("10.00");
        assertThat(result.appliedPromotions().getFirst().rewardQuantity()).isEqualTo(2);
        var withoutReward = calculator.calculate(basket(new PromotionCalculationRequest.Line(1L, 4)),
                List.of(snapshot(1L, "10.00")), List.of(promotion), List.of(rule), LocalDateTime.now());
        assertThat(withoutReward.discountTotal()).isEqualByComparingTo("0");
        assertThat(withoutReward.appliedPromotions()).isEmpty();
    }

    private PromotionCalculationRequest basket(PromotionCalculationRequest.Line... lines) {
        return new PromotionCalculationRequest(9L, List.of(lines));
    }

    private VariantSnapshotResponse snapshot(Long id, String price) {
        return new VariantSnapshotResponse(id, 10L, 9L, 11L, "P", "Cake", "Box", "SKU", null,
                new BigDecimal(price), 20, 20, "ACTIVE", "ACTIVE", "ACTIVE");
    }

    private PromotionEntity promotion(Long id) {
        PromotionEntity entity = new PromotionEntity();
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setName("Promotion " + id);
        return entity;
    }

    private PromotionRuleEntity rule(Long id, Long promotionId, PromotionType type, Long target) {
        PromotionRuleEntity entity = new PromotionRuleEntity();
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setPromotionId(promotionId);
        entity.setRuleType(type);
        entity.setTargetVariantId(target);
        return entity;
    }

    private PromotionRuleEntity buy(Long id, Long promotionId, Long target, Long reward, int min, int quantity) {
        var entity = rule(id, promotionId, PromotionType.BUY_X_GET_Y, target);
        entity.setRewardVariantId(reward);
        entity.setMinQuantity(min);
        entity.setRewardQuantity(quantity);
        return entity;
    }

    private PromotionRuleEntity fixed(Long id, Long promotionId, Long target, String amount) {
        var entity = rule(id, promotionId, PromotionType.FIXED_DISCOUNT, target);
        entity.setDiscountAmount(new BigDecimal(amount));
        return entity;
    }

    private PromotionRuleEntity percent(Long id, Long promotionId, Long target, String percent) {
        var entity = rule(id, promotionId, PromotionType.PERCENT_DISCOUNT, target);
        entity.setDiscountPercent(new BigDecimal(percent));
        return entity;
    }
}
