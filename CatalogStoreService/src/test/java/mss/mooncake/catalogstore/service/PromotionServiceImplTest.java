package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.dto.request.CreatePromotionRequest;
import mss.mooncake.catalogstore.dto.request.PromotionRuleRequest;
import mss.mooncake.catalogstore.dto.request.UpdatePromotionRequest;
import mss.mooncake.catalogstore.dto.response.PromotionResponse;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.service.impl.PromotionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionServiceImpl Unit Tests")
class PromotionServiceImplTest {

    @Mock private PromotionRepository promotionRepository;
    @Mock private PromotionRuleRepository ruleRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private StoreAccessService storeAccessService;

    private PromotionServiceImpl promotionService;

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long STORE_ID = 1L;
    private static final Long OWNER = 10L;
    private static final Long STRANGER = 20L;
    /** "Bây giờ" cố định: 2026-09-01 00:00 UTC. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 0, 0);

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
        promotionService = new PromotionServiceImpl(promotionRepository, ruleRepository, variantRepository,
                storeAccessService, fixed);
    }

    private static PromotionRuleRequest percent(String pct, Long target) {
        return new PromotionRuleRequest(PromotionType.PERCENT_DISCOUNT, target, null, null, new BigDecimal(pct), null, null);
    }

    private static PromotionRuleRequest fixed(String amount) {
        return new PromotionRuleRequest(PromotionType.FIXED_DISCOUNT, null, null, null, null, new BigDecimal(amount), null);
    }

    private static PromotionRuleRequest buyXGetY(Long target, Long reward, Integer x, Integer y) {
        return new PromotionRuleRequest(PromotionType.BUY_X_GET_Y, target, reward, x, null, null, y);
    }

    private static CreatePromotionRequest createRequest(List<PromotionRuleRequest> rules) {
        return new CreatePromotionRequest(STORE_ID, "Trung thu 2026", NOW.plusDays(1), NOW.plusDays(30), rules);
    }

    private static PromotionEntity promotion(Long id, PromotionStatus status, LocalDateTime endAt) {
        PromotionEntity p = new PromotionEntity();
        ReflectionTestUtils.setField(p, "id", id);
        p.setStoreId(STORE_ID);
        p.setName("KM " + id);
        p.setStartAt(NOW.minusDays(10));
        p.setEndAt(endAt);
        p.setStatus(status);
        return p;
    }

    private void ownerAndSaveSucceeds() {
        when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
        when(promotionRepository.save(any(PromotionEntity.class))).thenAnswer(inv -> {
            PromotionEntity saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 30L);
            return saved;
        });
        when(ruleRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("createPromotion — CA18")
    class Create {

        @Test
        @DisplayName("tạo DRAFT với đủ ba loại rule, target/reward cùng store")
        void createWithAllRuleTypes() {
            ownerAndSaveSucceeds();
            when(variantRepository.countInStore(eq(STORE_ID), anyCollection())).thenReturn(3L);

            PromotionResponse response = promotionService.createPromotion(createRequest(List.of(
                    percent("10", 7L), fixed("20000"), buyXGetY(8L, 9L, 2, 1))), OWNER);

            assertThat(response.promotionId()).isEqualTo(30L);
            assertThat(response.status()).isEqualTo("DRAFT");
            assertThat(response.rules()).extracting(r -> r.ruleType())
                    .containsExactly("PERCENT_DISCOUNT", "FIXED_DISCOUNT", "BUY_X_GET_Y");
            verify(variantRepository).countInStore(STORE_ID, List.of(7L, 8L, 9L));
        }

        @Test
        @DisplayName("variant thuộc store khác → 400, không ghi DB")
        void variantFromAnotherStore() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(variantRepository.countInStore(eq(STORE_ID), anyCollection())).thenReturn(1L);

            assertThatThrownBy(() -> promotionService.createPromotion(
                    createRequest(List.of(buyXGetY(8L, 999L, 2, 1))), OWNER))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("không thuộc store");
            verify(promotionRepository, never()).save(any());
        }

        @Test
        @DisplayName("startAt >= endAt → 400")
        void invalidPeriod() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());

            assertThatThrownBy(() -> promotionService.createPromotion(new CreatePromotionRequest(STORE_ID, "KM",
                    NOW.plusDays(5), NOW.plusDays(5), List.of(fixed("1000"))), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("không phải owner → 403")
        void notOwner() {
            when(storeAccessService.requireOwnedStore(STORE_ID, STRANGER)).thenThrow(new ForbiddenException("x"));

            assertThatThrownBy(() -> promotionService.createPromotion(createRequest(List.of(fixed("1000"))), STRANGER))
                    .isInstanceOf(ForbiddenException.class);
            verify(promotionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Kiểm tra field theo ruleType")
    class RuleFields {

        private void expectBadRequest(PromotionRuleRequest rule) {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            assertThatThrownBy(() -> promotionService.createPromotion(createRequest(List.of(rule)), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("PERCENT_DISCOUNT thiếu hoặc bằng 0 percent → 400")
        void percentMissing() {
            expectBadRequest(new PromotionRuleRequest(PromotionType.PERCENT_DISCOUNT, null, null, null, null, null, null));
            expectBadRequest(percent("0", null));
        }

        @Test
        @DisplayName("PERCENT_DISCOUNT kèm discountAmount → 400 (tránh rule mơ hồ)")
        void percentWithAmount() {
            expectBadRequest(new PromotionRuleRequest(PromotionType.PERCENT_DISCOUNT, null, null, null,
                    BigDecimal.TEN, BigDecimal.ONE, null));
        }

        @Test
        @DisplayName("FIXED_DISCOUNT thiếu discountAmount → 400")
        void fixedMissing() {
            expectBadRequest(new PromotionRuleRequest(PromotionType.FIXED_DISCOUNT, null, null, null, null, null, null));
        }

        @Test
        @DisplayName("BUY_X_GET_Y thiếu reward hoặc quantity → 400")
        void buyXGetYMissing() {
            expectBadRequest(buyXGetY(8L, null, 2, 1));
            expectBadRequest(buyXGetY(8L, 9L, null, 1));
        }

        @Test
        @DisplayName("danh sách rule null, rỗng hoặc quá 20 → 400")
        void ruleCount() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            assertThatThrownBy(() -> promotionService.createPromotion(createRequest(null), OWNER))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> promotionService.createPromotion(createRequest(List.of()), OWNER))
                    .isInstanceOf(BadRequestException.class);
            List<PromotionRuleRequest> tooMany = new ArrayList<>(Collections.nCopies(21, fixed("1000")));
            assertThatThrownBy(() -> promotionService.createPromotion(createRequest(tooMany), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("updatePromotion — CA19")
    class Update {

        @Test
        @DisplayName("DRAFT → ACTIVE khi end_at còn ở tương lai")
        void activate() {
            PromotionEntity p = promotion(30L, PromotionStatus.DRAFT, NOW.plusDays(5));
            when(promotionRepository.findById(30L)).thenReturn(Optional.of(p));
            when(promotionRepository.save(p)).thenReturn(p);

            PromotionResponse response = promotionService.updatePromotion(30L,
                    new UpdatePromotionRequest(null, null, null, "active"), OWNER);

            assertThat(response.status()).isEqualTo("ACTIVE");
            verify(storeAccessService).requireOwnedStore(STORE_ID, OWNER);
        }

        @Test
        @DisplayName("kích hoạt promotion đã kết thúc → 400")
        void activateEnded() {
            when(promotionRepository.findById(30L))
                    .thenReturn(Optional.of(promotion(30L, PromotionStatus.DRAFT, NOW.minusDays(1))));

            assertThatThrownBy(() -> promotionService.updatePromotion(30L,
                    new UpdatePromotionRequest(null, null, null, "ACTIVE"), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("về DRAFT, đặt tay EXPIRED, status lạ → 400")
        void forbiddenTransitions() {
            when(promotionRepository.findById(30L))
                    .thenReturn(Optional.of(promotion(30L, PromotionStatus.ACTIVE, NOW.plusDays(5))));

            for (String status : List.of("DRAFT", "EXPIRED", "PAUSED")) {
                assertThatThrownBy(() -> promotionService.updatePromotion(30L,
                        new UpdatePromotionRequest(null, null, null, status), OWNER))
                        .isInstanceOf(BadRequestException.class);
            }
        }

        @Test
        @DisplayName("chỉ đổi endAt về trước startAt hiện tại → 400")
        void endBeforeExistingStart() {
            when(promotionRepository.findById(30L))
                    .thenReturn(Optional.of(promotion(30L, PromotionStatus.DRAFT, NOW.plusDays(5))));

            assertThatThrownBy(() -> promotionService.updatePromotion(30L,
                    new UpdatePromotionRequest(null, null, NOW.minusDays(20), null), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("replaceRules — CA20")
    class ReplaceRules {

        @Test
        @DisplayName("xóa rule cũ rồi ghi rule mới, theo đúng thứ tự")
        void replace() {
            PromotionEntity p = promotion(30L, PromotionStatus.DRAFT, NOW.plusDays(5));
            when(promotionRepository.findById(30L)).thenReturn(Optional.of(p));
            when(ruleRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            PromotionResponse response = promotionService.replaceRules(30L, List.of(fixed("15000")), OWNER);

            assertThat(response.rules()).hasSize(1);
            InOrder order = inOrder(ruleRepository);
            order.verify(ruleRepository).deleteByPromotionId(30L);
            order.verify(ruleRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("rule không hợp lệ → 400, không xóa rule cũ")
        void invalidRulesKeepOld() {
            when(promotionRepository.findById(30L))
                    .thenReturn(Optional.of(promotion(30L, PromotionStatus.DRAFT, NOW.plusDays(5))));

            assertThatThrownBy(() -> promotionService.replaceRules(30L, List.of(fixed("0")), OWNER))
                    .isInstanceOf(BadRequestException.class);
            verify(ruleRepository, never()).deleteByPromotionId(any());
        }
    }

    @Nested
    @DisplayName("getPromotion — CA17")
    class Detail {

        @Test
        @DisplayName("public: DRAFT hoặc đã kết thúc → 404; owner vẫn xem được")
        void visibility() {
            PromotionEntity draft = promotion(30L, PromotionStatus.DRAFT, NOW.plusDays(5));
            PromotionEntity ended = promotion(31L, PromotionStatus.ACTIVE, NOW.minusDays(1));
            when(promotionRepository.findById(30L)).thenReturn(Optional.of(draft));
            when(promotionRepository.findById(31L)).thenReturn(Optional.of(ended));
            when(storeAccessService.isOwner(STORE_ID, OWNER)).thenReturn(true);
            when(ruleRepository.findByPromotionIdOrderByIdAsc(30L)).thenReturn(List.<PromotionRuleEntity>of());

            assertThatThrownBy(() -> promotionService.getPromotion(30L, null)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> promotionService.getPromotion(31L, null)).isInstanceOf(ResourceNotFoundException.class);
            assertThat(promotionService.getPromotion(30L, OWNER).status()).isEqualTo("DRAFT");
        }
    }
}
