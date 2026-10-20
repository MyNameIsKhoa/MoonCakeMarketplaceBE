package mss.mooncake.catalogstore.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult.LineResult;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.service.impl.PromotionCalculationServiceImpl.PricedLine;
import mss.mooncake.catalogstore.service.impl.PromotionCalculationServiceImpl.RuleView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PromotionCalculationServiceImpl.computeDiscounts — quy tắc tính IN08")
class PromotionDiscountRulesTest {


    private static final Long STORE = 1L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 10, 12, 0);

    // Variant: A = bánh nướng 100.000đ, B = bánh dẻo 80.000đ, C = hộp quà 50.000đ
    private static final Long A = 11L;
    private static final Long B = 12L;
    private static final Long C = 13L;

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static PricedLine line(Long variant, int qty, String price) {
        return new PricedLine(variant, qty, new BigDecimal(price));
    }

    private static RuleView percent(long ruleId, Long target, Integer minQty, String pct) {
        return new RuleView(100L, "KM %", ruleId, PromotionType.PERCENT_DISCOUNT, target, null, minQty,
                new BigDecimal(pct), null, null);
    }

    private static RuleView fixed(long ruleId, Long target, Integer minQty, String amount) {
        return new RuleView(200L, "KM tiền", ruleId, PromotionType.FIXED_DISCOUNT, target, null, minQty,
                null, new BigDecimal(amount), null);
    }

    private static RuleView buyXGetY(long ruleId, Long target, Long reward, int x, int y) {
        return new RuleView(300L, "Mua X tặng Y", ruleId, PromotionType.BUY_X_GET_Y, target, reward, x,
                null, null, y);
    }

    private PromotionCalculationResult calc(List<PricedLine> lines, RuleView... rules) {
        return PromotionCalculationServiceImpl.computeDiscounts(STORE, AT, lines, List.of(rules));
    }

    private static LineResult lineOf(PromotionCalculationResult r, Long variant) {
        return r.lines().stream().filter(l -> l.variantId().equals(variant)).findFirst().orElseThrow();
    }

    /** Bất biến luôn đúng: tổng giảm của line = discountTotal, total = subtotal - discountTotal, không âm. */
    private static void assertConsistent(PromotionCalculationResult r) {
        BigDecimal sumLines = r.lines().stream().map(LineResult::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumLines).isEqualByComparingTo(r.discountTotal());
        assertThat(r.totalAmount()).isEqualByComparingTo(r.subtotal().subtract(r.discountTotal()));
        assertThat(r.discountTotal()).isLessThanOrEqualTo(r.subtotal());
        r.lines().forEach(l -> assertThat(l.lineTotal().signum()).isGreaterThanOrEqualTo(0));
    }

    // ── Không có khuyến mãi ──────────────────────────────────────────────────────

    @Test
    @DisplayName("không có rule → giảm 0, total = subtotal")
    void noRules() {
        PromotionCalculationResult r = calc(List.of(line(A, 2, "100000"), line(B, 1, "80000")));

        assertThat(r.subtotal()).isEqualByComparingTo("280000");
        assertThat(r.discountTotal()).isEqualByComparingTo("0");
        assertThat(r.appliedPromotions()).isEmpty();
        assertThat(r.storeId()).isEqualTo(STORE);
        assertThat(r.calculatedAt()).isEqualTo(AT);
        assertConsistent(r);
    }

    // ── PERCENT_DISCOUNT ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PERCENT_DISCOUNT")
    class Percent {

        @Test
        @DisplayName("có target: chỉ giảm line target")
        void targetOnly() {
            PromotionCalculationResult r = calc(List.of(line(A, 2, "100000"), line(B, 1, "80000")),
                    percent(1, A, null, "10"));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("20000");
            assertThat(lineOf(r, B).discountAmount()).isEqualByComparingTo("0");
            assertThat(lineOf(r, A).promotionRuleId()).isEqualTo(1L);
            assertConsistent(r);
        }

        @Test
        @DisplayName("không target: giảm mọi line, gộp thành một applied promotion")
        void allLines() {
            PromotionCalculationResult r = calc(List.of(line(A, 2, "100000"), line(B, 1, "80000")),
                    percent(1, null, null, "10"));

            assertThat(r.discountTotal()).isEqualByComparingTo("28000");
            assertThat(r.appliedPromotions()).hasSize(1);
            assertThat(r.appliedPromotions().get(0).discountAmount()).isEqualByComparingTo("28000");
            assertConsistent(r);
        }

        @Test
        @DisplayName("chưa đủ minQuantity → không giảm")
        void minQuantityNotReached() {
            PromotionCalculationResult r = calc(List.of(line(A, 2, "100000")), percent(1, A, 3, "10"));

            assertThat(r.discountTotal()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("làm tròn HALF_UP tới đơn vị VND: 33.333 × 15% = 4.999,95 → 5.000")
        void roundingHalfUp() {
            PromotionCalculationResult r = calc(List.of(line(A, 1, "33333")), percent(1, A, null, "15"));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("5000");
            assertThat(lineOf(r, A).lineTotal()).isEqualByComparingTo("28333");
        }
    }

    // ── FIXED_DISCOUNT ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIXED_DISCOUNT")
    class Fixed {

        @Test
        @DisplayName("có target: giảm một lần cho cả line, không nhân theo số lượng")
        void perLineOnce() {
            PromotionCalculationResult r = calc(List.of(line(A, 3, "100000")), fixed(1, A, null, "25000"));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("25000");
        }

        @Test
        @DisplayName("giảm không vượt lineSubtotal")
        void cappedAtLineSubtotal() {
            PromotionCalculationResult r = calc(List.of(line(C, 1, "50000")), fixed(1, C, null, "70000"));

            assertThat(lineOf(r, C).discountAmount()).isEqualByComparingTo("50000");
            assertThat(lineOf(r, C).lineTotal()).isEqualByComparingTo("0");
            assertConsistent(r);
        }

        @Test
        @DisplayName("không target: giảm cả đơn một lần, phân bổ theo tỷ lệ, tổng phân bổ khớp đúng")
        void orderLevelAllocated() {
            // subtotal 300.000 (A 200.000, B 80.000, C 20.000), giảm 50.000
            PromotionCalculationResult r = calc(List.of(line(A, 2, "100000"), line(B, 1, "80000"), line(C, 1, "20000")),
                    fixed(1, null, null, "50000"));

            assertThat(r.discountTotal()).isEqualByComparingTo("50000");
            assertThat(lineOf(r, B).discountAmount()).isEqualByComparingTo("13333"); // 50.000 × 80/300, làm tròn xuống
            assertThat(lineOf(r, C).discountAmount()).isEqualByComparingTo("3333");
            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("33334"); // phần dư về line lớn nhất
            assertThat(r.appliedPromotions()).hasSize(1);
            assertConsistent(r);
        }

        @Test
        @DisplayName("giảm cả đơn cần tổng quantity ≥ minQuantity")
        void orderLevelMinQuantity() {
            PromotionCalculationResult r = calc(List.of(line(A, 1, "100000"), line(B, 1, "80000")),
                    fixed(1, null, 3, "50000"));

            assertThat(r.discountTotal()).isEqualByComparingTo("0");
        }
    }

    // ── BUY_X_GET_Y ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("BUY_X_GET_Y")
    class BuyXGetY {

        @Test
        @DisplayName("trigger khác reward: mua 2 A tặng 1 C, có 5 A và 3 C → tặng 2 C")
        void differentVariants() {
            PromotionCalculationResult r = calc(List.of(line(A, 5, "100000"), line(C, 3, "50000")),
                    buyXGetY(1, A, C, 2, 1));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("0");
            assertThat(lineOf(r, C).discountAmount()).isEqualByComparingTo("100000");
            var applied = r.appliedPromotions().get(0);
            assertThat(applied.rewardVariantId()).isEqualTo(C);
            assertThat(applied.rewardQuantity()).isEqualTo(2);
            assertConsistent(r);
        }

        @Test
        @DisplayName("số unit tặng không vượt số reward trong giỏ")
        void freeUnitsCappedByRewardInCart() {
            PromotionCalculationResult r = calc(List.of(line(A, 10, "100000"), line(C, 1, "50000")),
                    buyXGetY(1, A, C, 2, 1));

            assertThat(lineOf(r, C).discountAmount()).isEqualByComparingTo("50000");
            assertThat(r.appliedPromotions().get(0).rewardQuantity()).isEqualTo(1);
        }

        @Test
        @DisplayName("reward không có trong giỏ → không giảm")
        void rewardNotInCart() {
            PromotionCalculationResult r = calc(List.of(line(A, 4, "100000")), buyXGetY(1, A, C, 2, 1));

            assertThat(r.discountTotal()).isEqualByComparingTo("0");
            assertThat(r.appliedPromotions()).isEmpty();
        }

        @Test
        @DisplayName("chưa đủ X → không giảm")
        void triggerNotReached() {
            PromotionCalculationResult r = calc(List.of(line(A, 1, "100000"), line(C, 1, "50000")),
                    buyXGetY(1, A, C, 2, 1));

            assertThat(r.discountTotal()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("cùng variant: mua 2 tặng 1, có 7 cái → 2 nhóm (2+1) → tặng 2")
        void sameVariant() {
            PromotionCalculationResult r = calc(List.of(line(A, 7, "100000")), buyXGetY(1, A, A, 2, 1));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("200000");
            assertThat(r.appliedPromotions().get(0).rewardQuantity()).isEqualTo(2);
            assertConsistent(r);
        }
    }

    // ── Chọn rule & không cộng dồn ───────────────────────────────────────────────

    @Nested
    @DisplayName("Chọn rule tốt nhất, không cộng dồn")
    class BestRule {

        @Test
        @DisplayName("một line có nhiều rule → chỉ áp rule giảm nhiều nhất")
        void bestRulePerLine() {
            // A: 2 × 100.000 = 200.000; 10% = 20.000 < fixed 30.000
            PromotionCalculationResult r = calc(List.of(line(A, 2, "100000")),
                    percent(1, A, null, "10"), fixed(2, A, null, "30000"));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("30000");
            assertThat(lineOf(r, A).promotionRuleId()).isEqualTo(2L);
            assertThat(r.appliedPromotions()).hasSize(1);
        }

        @Test
        @DisplayName("bằng nhau → chọn ruleId nhỏ hơn (kết quả ổn định)")
        void tieBreakByRuleId() {
            PromotionCalculationResult r = calc(List.of(line(A, 1, "100000")),
                    fixed(5, A, null, "10000"), percent(3, A, null, "10"));

            assertThat(lineOf(r, A).promotionRuleId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("giảm cả đơn lớn hơn tổng giảm theo line → chọn giảm cả đơn, bỏ rule theo line")
        void orderLevelWins() {
            PromotionCalculationResult r = calc(List.of(line(A, 1, "100000"), line(B, 1, "80000")),
                    percent(1, A, null, "10"), fixed(2, null, null, "50000"));

            assertThat(r.discountTotal()).isEqualByComparingTo("50000");
            assertThat(r.appliedPromotions()).extracting(a -> a.promotionRuleId()).containsExactly(2L);
            assertConsistent(r);
        }

        @Test
        @DisplayName("tổng giảm theo line lớn hơn giảm cả đơn → giữ rule theo line")
        void perLineWins() {
            PromotionCalculationResult r = calc(List.of(line(A, 1, "100000"), line(B, 1, "80000")),
                    percent(1, null, null, "20"), fixed(2, null, null, "30000"));

            assertThat(r.discountTotal()).isEqualByComparingTo("36000");
            assertThat(r.appliedPromotions()).extracting(a -> a.promotionRuleId()).containsExactly(1L);
        }

        @Test
        @DisplayName("line trigger và line reward có thể nhận rule khác nhau")
        void triggerAndRewardLinesIndependent() {
            PromotionCalculationResult r = calc(List.of(line(A, 2, "100000"), line(C, 1, "50000")),
                    buyXGetY(1, A, C, 2, 1), percent(2, A, null, "10"));

            assertThat(lineOf(r, A).discountAmount()).isEqualByComparingTo("20000");
            assertThat(lineOf(r, C).discountAmount()).isEqualByComparingTo("50000");
            assertThat(r.discountTotal()).isEqualByComparingTo("70000");
            assertConsistent(r);
        }
    }
}
