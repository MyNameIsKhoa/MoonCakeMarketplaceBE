package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PromotionCalculationServiceTest {
    @Autowired private PromotionCalculationService calculator;
    @Autowired private StoreRepository stores;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;
    @Autowired private PromotionRepository promotions;
    @Autowired private PromotionRuleRepository rules;

    @Test
    void usesCatalogPriceAndOnlyEffectiveRulesForItsStore() {
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(88L);
        store.setStoreCode("PRICING-STORE");
        store.setName("Pricing store");
        store.setSlug("pricing-store");
        store.setStatus(StoreStatus.ACTIVE);
        store = stores.saveAndFlush(store);
        ProductEntity product = new ProductEntity();
        product.setStoreId(store.getId());
        product.setCategoryId(1L);
        product.setProductCode("PRICING-PRODUCT");
        product.setName("Cake");
        product.setStatus(ProductStatus.ACTIVE);
        product = products.saveAndFlush(product);
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProductId(product.getId());
        variant.setSku("PRICING-SKU");
        variant.setVariantName("Box");
        variant.setBasePrice(new BigDecimal("10.60"));
        variant.setStockQuantity(10);
        variant.setStatus(ProductStatus.ACTIVE);
        variant = variants.saveAndFlush(variant);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PromotionEntity active = promotion(store.getId(), now.minusDays(1), now.plusDays(1),
                PromotionStatus.ACTIVE);
        PromotionRuleEntity fixed = new PromotionRuleEntity();
        fixed.setPromotionId(active.getId());
        fixed.setRuleType(PromotionType.FIXED_DISCOUNT);
        fixed.setDiscountAmount(new BigDecimal("1.00"));
        rules.saveAndFlush(fixed);
        promotion(store.getId(), now.minusDays(1), now.plusDays(1), PromotionStatus.DRAFT);
        PromotionEntity expired = promotion(store.getId(), now.minusDays(2), now.minusDays(1),
                PromotionStatus.ACTIVE);
        PromotionRuleEntity ignored = new PromotionRuleEntity();
        ignored.setPromotionId(expired.getId());
        ignored.setRuleType(PromotionType.FIXED_DISCOUNT);
        ignored.setDiscountAmount(new BigDecimal("10.00"));
        rules.saveAndFlush(ignored);
        var request = new PromotionCalculationRequest(store.getId(),
                List.of(new PromotionCalculationRequest.Line(variant.getId(), 1)));
        var result = calculator.calculate(request);
        assertThat(result.subtotal()).isEqualByComparingTo("10.60");
        assertThat(result.discountTotal()).isEqualByComparingTo("1.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("10");
        assertThat(result.appliedPromotions()).hasSize(1);
        Long otherStoreId = store.getId() + 1000;
        assertThatThrownBy(() -> calculator.calculate(new PromotionCalculationRequest(otherStoreId,
                request.lines()))).isInstanceOf(BadRequestException.class);
    }

    private PromotionEntity promotion(Long storeId, LocalDateTime start, LocalDateTime end,
            PromotionStatus status) {
        PromotionEntity promotion = new PromotionEntity();
        promotion.setStoreId(storeId);
        promotion.setName("Promotion");
        promotion.setStartAt(start);
        promotion.setEndAt(end);
        promotion.setStatus(status);
        return promotions.saveAndFlush(promotion);
    }
}
