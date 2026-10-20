package mss.mooncake.catalogstore.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult.AppliedPromotion;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult.LineResult;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.service.PromotionCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IN08: nạp giá variant + promotion đang hiệu lực từ DB Catalog rồi tính bằng {@link #computeDiscounts}.
 * Không kiểm tra trạng thái bán (ACTIVE/tồn kho): Commerce kiểm tra qua IN07 trước khi checkout.
 *
 * <p>Quy tắc tính MVP:
 * <ul>
 *   <li>Mỗi line nhận tối đa một rule: rule giảm nhiều nhất, hòa thì ruleId nhỏ hơn.</li>
 *   <li>PERCENT_DISCOUNT: giảm lineSubtotal × percent / 100 trên line target (target null = mọi line);
 *       cần quantity của line ≥ minQuantity (mặc định 1).</li>
 *   <li>FIXED_DISCOUNT có target: giảm discountAmount một lần cho cả line (không nhân theo unit).</li>
 *   <li>FIXED_DISCOUNT không target: giảm một lần cho cả đơn của store khi tổng quantity ≥ minQuantity.
 *       Không cộng dồn với rule theo line: chọn phương án có tổng giảm lớn hơn (hòa thì giữ phương án theo line),
 *       phân bổ về các line theo tỷ lệ lineSubtotal.</li>
 *   <li>BUY_X_GET_Y: mua minQuantity target thì được rewardQuantity reward, áp dụng nhiều lần.
 *       Giảm nằm trên line reward = số unit miễn phí × đơn giá reward; reward phải có trong giỏ.
 *       Target trùng reward thì tính theo nhóm (X + Y): free = floor(qty / (X + Y)) × Y.</li>
 *   <li>Tiền giảm làm tròn tới đơn vị VND (HALF_UP) và không vượt lineSubtotal; discountTotal ≤ subtotal.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PromotionCalculationServiceImpl implements PromotionCalculationService {

    /** Một line đã gộp theo variant; unitPrice lấy từ Catalog, không nhận từ caller. */
    record PricedLine(Long variantId, int quantity, BigDecimal unitPrice) { }

    /** Rule đang hiệu lực kèm thông tin promotion cha. */
    record RuleView(Long promotionId, String promotionName, Long ruleId, PromotionType type,
                    Long targetVariantId, Long rewardVariantId, Integer minQuantity,
                    BigDecimal discountPercent, BigDecimal discountAmount, Integer rewardQuantity) { }

    private record Candidate(RuleView rule, BigDecimal discount, int freeUnits) { }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparing(Candidate::discount).reversed()
            .thenComparing(c -> c.rule().ruleId());

    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final PromotionRepository promotionRepository;
    private final PromotionRuleRepository ruleRepository;
    private final Clock clock;

    public PromotionCalculationServiceImpl(ProductVariantRepository variantRepository,
                                           ProductRepository productRepository,
                                           PromotionRepository promotionRepository,
                                           PromotionRuleRepository ruleRepository,
                                           Clock clock) {
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.promotionRepository = promotionRepository;
        this.ruleRepository = ruleRepository;
        this.clock = clock;
    }

    // ─── Public API ───────────────────────────────────────────────────────────────

    @Override
    public PromotionCalculationResult calculate(PromotionCalculationRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. Gộp line trùng variant (cộng quantity), giữ thứ tự xuất hiện đầu tiên.
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        request.lines().forEach(l -> quantities.merge(l.variantId(), l.quantity(), Integer::sum));

        // 2. Variant phải tồn tại và thuộc đúng store của request.
        Map<Long, ProductVariantEntity> variants = variantRepository.findByIdIn(quantities.keySet()).stream()
                .collect(Collectors.toMap(ProductVariantEntity::getId, Function.identity()));
        List<Long> missing = quantities.keySet().stream().filter(id -> !variants.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy biến thể: " + missing);
        }
        Map<Long, Long> storeOfProduct = productRepository.findAllById(
                        variants.values().stream().map(ProductVariantEntity::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, ProductEntity::getStoreId));
        List<Long> foreign = variants.values().stream()
                .filter(v -> !request.storeId().equals(storeOfProduct.get(v.getProductId())))
                .map(ProductVariantEntity::getId)
                .sorted()
                .toList();
        if (!foreign.isEmpty()) {
            throw new BadRequestException("Biến thể không thuộc store " + request.storeId() + ": " + foreign);
        }

        List<PricedLine> lines = quantities.entrySet().stream()
                .map(e -> new PricedLine(e.getKey(), e.getValue(), variants.get(e.getKey()).getBasePrice()))
                .toList();

        // 3. Rule của các promotion ACTIVE có start_at <= now < end_at.
        Map<Long, PromotionEntity> promotions = promotionRepository
                .findEffective(request.storeId(), PromotionStatus.ACTIVE, now).stream()
                .collect(Collectors.toMap(PromotionEntity::getId, Function.identity()));
        List<RuleView> rules = promotions.isEmpty()
                ? List.of()
                : ruleRepository.findByPromotionIdIn(promotions.keySet()).stream()
                        .map(r -> new RuleView(r.getPromotionId(), promotions.get(r.getPromotionId()).getName(),
                                r.getId(), r.getRuleType(), r.getTargetVariantId(), r.getRewardVariantId(),
                                r.getMinQuantity(), r.getDiscountPercent(), r.getDiscountAmount(),
                                r.getRewardQuantity()))
                        .toList();

        return computeDiscounts(request.storeId(), now, lines, rules);
    }

    // ─── Tính khuyến mãi (thuần, không truy cập DB; unit test gọi trực tiếp) ────────

    static PromotionCalculationResult computeDiscounts(Long storeId, LocalDateTime calculatedAt,
                                                       List<PricedLine> lines, List<RuleView> rules) {
        Map<Long, PricedLine> byVariant = lines.stream()
                .collect(Collectors.toMap(PricedLine::variantId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        BigDecimal subtotal = lines.stream()
                .map(PromotionCalculationServiceImpl::lineSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Phương án A: mỗi line một rule tốt nhất.
        Map<Long, Candidate> perLine = new LinkedHashMap<>();
        for (PricedLine line : lines) {
            rules.stream()
                    .map(rule -> evaluateForLine(rule, line, byVariant))
                    .flatMap(Optional::stream)
                    .min(BEST_FIRST)
                    .ifPresent(best -> perLine.put(line.variantId(), best));
        }
        BigDecimal perLineTotal = perLine.values().stream()
                .map(Candidate::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Phương án B: một FIXED_DISCOUNT cho cả đơn.
        int totalQuantity = lines.stream().mapToInt(PricedLine::quantity).sum();
        Optional<Candidate> orderLevel = rules.stream()
                .filter(r -> r.type() == PromotionType.FIXED_DISCOUNT && r.targetVariantId() == null)
                .filter(r -> totalQuantity >= minQuantity(r))
                .map(r -> new Candidate(r, roundMoney(r.discountAmount()).min(subtotal), 0))
                .filter(c -> c.discount().signum() > 0)
                .min(BEST_FIRST);

        Map<Long, Candidate> assignment = orderLevel.isPresent() && orderLevel.get().discount().compareTo(perLineTotal) > 0
                ? allocate(orderLevel.get(), lines, subtotal)
                : perLine;

        return buildResult(storeId, calculatedAt, lines, subtotal, assignment);
    }

    private static Optional<Candidate> evaluateForLine(RuleView rule, PricedLine line, Map<Long, PricedLine> byVariant) {
        BigDecimal lineSubtotal = lineSubtotal(line);
        return switch (rule.type()) {
            case PERCENT_DISCOUNT -> {
                if (!targets(rule, line) || line.quantity() < minQuantity(rule)) yield Optional.empty();
                BigDecimal discount = roundMoney(lineSubtotal.multiply(rule.discountPercent()).divide(HUNDRED));
                yield positive(rule, discount.min(lineSubtotal), 0);
            }
            case FIXED_DISCOUNT -> {
                // Không target = giảm cả đơn, xử lý ở phương án B.
                if (rule.targetVariantId() == null || !targets(rule, line) || line.quantity() < minQuantity(rule)) {
                    yield Optional.empty();
                }
                yield positive(rule, roundMoney(rule.discountAmount()).min(lineSubtotal), 0);
            }
            case BUY_X_GET_Y -> {
                if (!line.variantId().equals(rule.rewardVariantId())) yield Optional.empty();
                int x = rule.minQuantity();
                int y = rule.rewardQuantity();
                int freeUnits;
                if (rule.rewardVariantId().equals(rule.targetVariantId())) {
                    freeUnits = (line.quantity() / (x + y)) * y;
                } else {
                    PricedLine trigger = byVariant.get(rule.targetVariantId());
                    int times = trigger == null ? 0 : trigger.quantity() / x;
                    freeUnits = Math.min(times * y, line.quantity());
                }
                BigDecimal discount = roundMoney(line.unitPrice().multiply(BigDecimal.valueOf(freeUnits)));
                yield positive(rule, discount.min(lineSubtotal), freeUnits);
            }
        };
    }

    private static boolean targets(RuleView rule, PricedLine line) {
        return rule.targetVariantId() == null || rule.targetVariantId().equals(line.variantId());
    }

    private static int minQuantity(RuleView rule) {
        return rule.minQuantity() == null ? 1 : rule.minQuantity();
    }

    private static Optional<Candidate> positive(RuleView rule, BigDecimal discount, int freeUnits) {
        return discount.signum() > 0 ? Optional.of(new Candidate(rule, discount, freeUnits)) : Optional.empty();
    }

    /**
     * Chia discount theo tỷ lệ lineSubtotal, làm tròn xuống từng phần; phần dư dồn vào line có subtotal lớn nhất
     * để tổng phân bổ khớp đúng discount.
     */
    private static Map<Long, Candidate> allocate(Candidate order, List<PricedLine> lines, BigDecimal subtotal) {
        Map<Long, Candidate> result = new LinkedHashMap<>();
        if (subtotal.signum() == 0) return result;
        PricedLine largest = lines.stream()
                .max(Comparator.comparing(PromotionCalculationServiceImpl::lineSubtotal))
                .orElseThrow();
        BigDecimal allocated = BigDecimal.ZERO;
        for (PricedLine line : lines) {
            if (line == largest) continue;
            BigDecimal share = order.discount().multiply(lineSubtotal(line)).divide(subtotal, 0, RoundingMode.DOWN);
            allocated = allocated.add(share);
            if (share.signum() > 0) result.put(line.variantId(), new Candidate(order.rule(), share, 0));
        }
        BigDecimal remainder = order.discount().subtract(allocated);
        if (remainder.signum() > 0) result.put(largest.variantId(), new Candidate(order.rule(), remainder, 0));
        return result;
    }

    private static PromotionCalculationResult buildResult(Long storeId, LocalDateTime calculatedAt, List<PricedLine> lines,
                                                          BigDecimal subtotal, Map<Long, Candidate> assignment) {
        List<LineResult> lineResults = new ArrayList<>();
        // Gộp discount của cùng một rule trên nhiều line (ví dụ PERCENT không target, giảm cả đơn).
        Map<Long, RuleView> appliedRules = new LinkedHashMap<>();
        Map<Long, BigDecimal> appliedDiscount = new LinkedHashMap<>();
        Map<Long, Integer> appliedFreeUnits = new LinkedHashMap<>();
        BigDecimal discountTotal = BigDecimal.ZERO;

        for (PricedLine line : lines) {
            BigDecimal lineSubtotal = lineSubtotal(line);
            Candidate c = assignment.get(line.variantId());
            BigDecimal discount = c == null ? BigDecimal.ZERO : c.discount();
            discountTotal = discountTotal.add(discount);
            lineResults.add(new LineResult(line.variantId(), line.quantity(), line.unitPrice(), lineSubtotal,
                    discount, lineSubtotal.subtract(discount),
                    c == null ? null : c.rule().promotionId(), c == null ? null : c.rule().ruleId()));
            if (c != null) {
                Long ruleId = c.rule().ruleId();
                appliedRules.putIfAbsent(ruleId, c.rule());
                appliedDiscount.merge(ruleId, c.discount(), BigDecimal::add);
                appliedFreeUnits.merge(ruleId, c.freeUnits(), Integer::sum);
            }
        }

        List<AppliedPromotion> appliedPromotions = appliedRules.values().stream().map(rule -> {
            boolean reward = rule.type() == PromotionType.BUY_X_GET_Y;
            return new AppliedPromotion(rule.promotionId(), rule.promotionName(), rule.ruleId(), rule.type().name(),
                    appliedDiscount.get(rule.ruleId()),
                    reward ? rule.rewardVariantId() : null, reward ? appliedFreeUnits.get(rule.ruleId()) : null);
        }).toList();
        return new PromotionCalculationResult(storeId, calculatedAt, subtotal, discountTotal,
                subtotal.subtract(discountTotal), lineResults, appliedPromotions);
    }

    private static BigDecimal lineSubtotal(PricedLine line) {
        return line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
    }

    private static BigDecimal roundMoney(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }
}
