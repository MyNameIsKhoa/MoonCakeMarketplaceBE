package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest.Line;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.service.impl.PromotionCalculationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionCalculationServiceImpl Unit Tests")
class PromotionCalculationServiceImplTest {

    @Mock private ProductVariantRepository variantRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PromotionRepository promotionRepository;
    @Mock private PromotionRuleRepository ruleRepository;

    private PromotionCalculationServiceImpl service;

    private static final Long STORE = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 12, 0);

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);
        service = new PromotionCalculationServiceImpl(variantRepository, productRepository, promotionRepository,
                ruleRepository, fixed);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static ProductVariantEntity variant(Long id, Long productId, String price) {
        ProductVariantEntity v = new ProductVariantEntity();
        ReflectionTestUtils.setField(v, "id", id);
        v.setProductId(productId);
        v.setBasePrice(new BigDecimal(price));
        return v;
    }

    private static ProductEntity product(Long id, Long storeId) {
        ProductEntity p = new ProductEntity();
        ReflectionTestUtils.setField(p, "id", id);
        p.setStoreId(storeId);
        return p;
    }

    @Test
    @DisplayName("gộp line trùng variant, giá lấy từ DB, chỉ dùng promotion hiệu lực tại now của Clock")
    void mergesLinesAndUsesEffectivePromotions() {
        when(variantRepository.findByIdIn(anyCollection())).thenReturn(List.of(variant(11L, 5L, "100000")));
        when(productRepository.findAllById(List.of(5L))).thenReturn(List.of(product(5L, STORE)));
        PromotionEntity promo = new PromotionEntity();
        ReflectionTestUtils.setField(promo, "id", 100L);
        promo.setName("Trung thu");
        when(promotionRepository.findEffective(STORE, PromotionStatus.ACTIVE, NOW)).thenReturn(List.of(promo));
        PromotionRuleEntity rule = new PromotionRuleEntity();
        ReflectionTestUtils.setField(rule, "id", 1L);
        rule.setPromotionId(100L);
        rule.setRuleType(PromotionType.PERCENT_DISCOUNT);
        rule.setDiscountPercent(new BigDecimal("10"));
        when(ruleRepository.findByPromotionIdIn(anyCollection())).thenReturn(List.of(rule));

        PromotionCalculationResult r = service.calculate(new PromotionCalculationRequest(STORE,
                List.of(new Line(11L, 1), new Line(11L, 2))));

        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).quantity()).isEqualTo(3);
        assertThat(r.subtotal()).isEqualByComparingTo("300000");
        assertThat(r.discountTotal()).isEqualByComparingTo("30000");
        assertThat(r.appliedPromotions().get(0).promotionName()).isEqualTo("Trung thu");
        assertThat(r.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("không có promotion hiệu lực → không truy vấn rule, giảm 0")
    void noEffectivePromotions() {
        when(variantRepository.findByIdIn(anyCollection())).thenReturn(List.of(variant(11L, 5L, "100000")));
        when(productRepository.findAllById(List.of(5L))).thenReturn(List.of(product(5L, STORE)));
        when(promotionRepository.findEffective(STORE, PromotionStatus.ACTIVE, NOW)).thenReturn(List.of());

        PromotionCalculationResult r = service.calculate(new PromotionCalculationRequest(STORE, List.of(new Line(11L, 1))));

        assertThat(r.discountTotal()).isEqualByComparingTo("0");
        verify(ruleRepository, never()).findByPromotionIdIn(any());
    }

    @Test
    @DisplayName("variant không tồn tại → 404 liệt kê ID thiếu")
    void missingVariant() {
        when(variantRepository.findByIdIn(anyCollection())).thenReturn(List.of(variant(11L, 5L, "100000")));

        assertThatThrownBy(() -> service.calculate(new PromotionCalculationRequest(STORE,
                List.of(new Line(11L, 1), new Line(99L, 1)))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("variant thuộc store khác → 400")
    void variantOfAnotherStore() {
        when(variantRepository.findByIdIn(anyCollection())).thenReturn(List.of(variant(11L, 5L, "100000")));
        when(productRepository.findAllById(List.of(5L))).thenReturn(List.of(product(5L, 2L)));

        assertThatThrownBy(() -> service.calculate(new PromotionCalculationRequest(STORE, List.of(new Line(11L, 1)))))
                .isInstanceOf(BadRequestException.class);
        verify(promotionRepository, never()).findEffective(any(), any(), any());
    }
}
