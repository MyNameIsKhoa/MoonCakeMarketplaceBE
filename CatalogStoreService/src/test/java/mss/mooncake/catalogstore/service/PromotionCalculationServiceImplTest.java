package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.service.impl.PromotionCalculationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PromotionCalculationServiceImplTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 12, 0);
    private final CatalogApplicationService catalog = mock(CatalogApplicationService.class);
    private final PromotionRepository promotions = mock(PromotionRepository.class);
    private final PromotionRuleRepository rules = mock(PromotionRuleRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
    private final PromotionCalculationServiceImpl service =
            new PromotionCalculationServiceImpl(catalog, promotions, rules, clock);

    @Test
    void mergesDuplicateLinesAndUsesCatalogClockAndEffectivePromotion() {
        when(catalog.getVariantSnapshots(List.of(11L))).thenReturn(List.of(snapshot(1L)));
        var promotion = new PromotionEntity();
        ReflectionTestUtils.setField(promotion, "id", 100L);
        promotion.setName("Trung thu");
        when(promotions.findEffective(1L, PromotionStatus.ACTIVE, NOW)).thenReturn(List.of(promotion));
        var rule = new PromotionRuleEntity();
        ReflectionTestUtils.setField(rule, "id", 1L);
        rule.setPromotionId(100L);
        rule.setRuleType(PromotionType.PERCENT_DISCOUNT);
        rule.setDiscountPercent(new BigDecimal("10"));
        when(rules.findByPromotionIdIn(List.of(100L))).thenReturn(List.of(rule));

        var result = service.calculate(new PromotionCalculationRequest(1L,
                List.of(new PromotionCalculationRequest.Line(11L, 1),
                        new PromotionCalculationRequest.Line(11L, 2))));

        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst().quantity()).isEqualTo(3);
        assertThat(result.subtotal()).isEqualByComparingTo("300000");
        assertThat(result.discountTotal()).isEqualByComparingTo("30000");
        assertThat(result.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsVariantFromAnotherStoreBeforePricing() {
        when(catalog.getVariantSnapshots(List.of(11L))).thenReturn(List.of(snapshot(2L)));
        assertThatThrownBy(() -> service.calculate(new PromotionCalculationRequest(1L,
                List.of(new PromotionCalculationRequest.Line(11L, 1)))))
                .isInstanceOf(BadRequestException.class);
    }

    private VariantSnapshotResponse snapshot(Long storeId) {
        return new VariantSnapshotResponse(11L, 5L, storeId, 7L, "P", "Cake", "Box", "SKU", null,
                new BigDecimal("100000"), 10, 10, "ACTIVE", "ACTIVE", "ACTIVE");
    }
}
