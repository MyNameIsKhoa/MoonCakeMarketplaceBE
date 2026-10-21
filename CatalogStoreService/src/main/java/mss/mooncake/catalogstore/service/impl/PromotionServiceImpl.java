package mss.mooncake.catalogstore.service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mss.mooncake.catalogstore.dto.request.CreatePromotionRequest;
import mss.mooncake.catalogstore.dto.request.PromotionRuleRequest;
import mss.mooncake.catalogstore.dto.request.UpdatePromotionRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.PromotionResponse;
import mss.mooncake.catalogstore.dto.response.PromotionRuleResponse;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.service.PromotionService;
import mss.mooncake.catalogstore.service.StoreAccessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class PromotionServiceImpl implements PromotionService {

    static final int MAX_RULES = 20;

    private final PromotionRepository promotionRepository;
    private final PromotionRuleRepository ruleRepository;
    private final ProductVariantRepository variantRepository;
    private final StoreAccessService storeAccessService;
    private final Clock clock;

    public PromotionServiceImpl(PromotionRepository promotionRepository,
                                PromotionRuleRepository ruleRepository,
                                ProductVariantRepository variantRepository,
                                StoreAccessService storeAccessService,
                                Clock clock) {
        this.promotionRepository = promotionRepository;
        this.ruleRepository = ruleRepository;
        this.variantRepository = variantRepository;
        this.storeAccessService = storeAccessService;
        this.clock = clock;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private PromotionEntity requirePromotion(Long promotionId) {
        return promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khuyến mãi với id: " + promotionId));
    }

    private static PromotionRuleResponse toRuleResponse(PromotionRuleEntity r) {
        return new PromotionRuleResponse(r.getId(), r.getRuleType().name(), r.getTargetVariantId(),
                r.getRewardVariantId(), r.getMinQuantity(), r.getDiscountPercent(), r.getDiscountAmount(),
                r.getRewardQuantity());
    }

    private static PromotionResponse toResponse(PromotionEntity p, List<PromotionRuleEntity> rules) {
        return new PromotionResponse(p.getId(), p.getStoreId(), p.getName(), p.getStartAt(), p.getEndAt(),
                p.getStatus().name(), rules.stream().map(PromotionServiceImpl::toRuleResponse).toList());
    }

    private PromotionResponse toResponse(PromotionEntity p) {
        return toResponse(p, ruleRepository.findByPromotionIdOrderByIdAsc(p.getId()));
    }

    /** Map một trang promotion, load rules của cả trang bằng một query. */
    private PageResponse<PromotionResponse> toPage(Page<PromotionEntity> page) {
        List<Long> ids = page.getContent().stream().map(PromotionEntity::getId).toList();
        Map<Long, List<PromotionRuleEntity>> rulesByPromotion = ids.isEmpty()
                ? Map.of()
                : ruleRepository.findByPromotionIdIn(ids).stream()
                        .collect(Collectors.groupingBy(PromotionRuleEntity::getPromotionId));
        return PageResponse.of(page, p -> toResponse(p, rulesByPromotion.getOrDefault(p.getId(), List.of())));
    }

    private static void requireValidPeriod(LocalDateTime startAt, LocalDateTime endAt) {
        if (!startAt.isBefore(endAt)) {
            throw new BadRequestException("Thời gian bắt đầu phải trước thời gian kết thúc.");
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /**
     * Kiểm tra field theo ruleType; field không dùng cho loại đó phải null để rule không bị hiểu sai.
     * targetVariantId null ở PERCENT/FIXED nghĩa là áp dụng cho mọi line của store.
     */
    private static void validateRuleFields(PromotionRuleRequest r, int index) {
        String at = "rules[" + index + "]: ";
        switch (r.ruleType()) {
            case PERCENT_DISCOUNT -> {
                if (!isPositive(r.discountPercent()) || r.discountPercent().compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new BadRequestException(at + "PERCENT_DISCOUNT cần discountPercent trong khoảng (0, 100].");
                }
                if (r.discountAmount() != null || r.rewardVariantId() != null || r.rewardQuantity() != null) {
                    throw new BadRequestException(at + "PERCENT_DISCOUNT không dùng discountAmount/rewardVariantId/rewardQuantity.");
                }
            }
            case FIXED_DISCOUNT -> {
                if (!isPositive(r.discountAmount())) {
                    throw new BadRequestException(at + "FIXED_DISCOUNT cần discountAmount > 0.");
                }
                if (r.discountPercent() != null || r.rewardVariantId() != null || r.rewardQuantity() != null) {
                    throw new BadRequestException(at + "FIXED_DISCOUNT không dùng discountPercent/rewardVariantId/rewardQuantity.");
                }
            }
            case BUY_X_GET_Y -> {
                if (r.targetVariantId() == null || r.rewardVariantId() == null
                        || r.minQuantity() == null || r.rewardQuantity() == null) {
                    throw new BadRequestException(at
                            + "BUY_X_GET_Y cần targetVariantId, rewardVariantId, minQuantity, rewardQuantity.");
                }
                if (r.discountPercent() != null || r.discountAmount() != null) {
                    throw new BadRequestException(at + "BUY_X_GET_Y không dùng discountPercent/discountAmount.");
                }
            }
        }
    }

    /** Kiểm tra số lượng rule, field theo loại và mọi target/reward variant thuộc cùng store. */
    private void validateRules(Long storeId, List<PromotionRuleRequest> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new BadRequestException("Khuyến mãi cần ít nhất một điều kiện (rule).");
        }
        if (rules.size() > MAX_RULES) {
            throw new BadRequestException("Tối đa " + MAX_RULES + " điều kiện cho một khuyến mãi.");
        }
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i) == null || rules.get(i).ruleType() == null) {
                throw new BadRequestException("rules[" + i + "]: thiếu ruleType.");
            }
            validateRuleFields(rules.get(i), i);
        }

        List<Long> variantIds = rules.stream()
                .flatMap(r -> Stream.of(r.targetVariantId(), r.rewardVariantId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!variantIds.isEmpty() && variantRepository.countInStore(storeId, variantIds) != variantIds.size()) {
            throw new BadRequestException("Có biến thể trong điều kiện khuyến mãi không thuộc store " + storeId + ".");
        }
    }

    private List<PromotionRuleEntity> saveRules(Long promotionId, List<PromotionRuleRequest> rules) {
        List<PromotionRuleEntity> entities = rules.stream().map(r -> {
            PromotionRuleEntity e = new PromotionRuleEntity();
            e.setPromotionId(promotionId);
            e.setRuleType(r.ruleType());
            e.setTargetVariantId(r.targetVariantId());
            e.setRewardVariantId(r.rewardVariantId());
            e.setMinQuantity(r.minQuantity());
            e.setDiscountPercent(r.discountPercent());
            e.setDiscountAmount(r.discountAmount());
            e.setRewardQuantity(r.rewardQuantity());
            return e;
        }).toList();
        return ruleRepository.saveAll(entities);
    }

    private PromotionStatus parseStatus(String status) {
        try {
            return PromotionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Trạng thái khuyến mãi không hợp lệ: " + status);
        }
    }

    /**
     * DRAFT → ACTIVE/DISABLED, ACTIVE ↔ DISABLED; không quay lại DRAFT.
     * EXPIRED không đặt tay (hết hạn suy ra từ end_at). Bật ACTIVE cần end_at còn ở tương lai.
     */
    private void validateStatusTransition(PromotionEntity promotion, PromotionStatus next) {
        PromotionStatus current = promotion.getStatus();
        if (current == next) return;
        if (next == PromotionStatus.DRAFT || next == PromotionStatus.EXPIRED || current == PromotionStatus.EXPIRED) {
            throw new BadRequestException("Cannot change promotion status from " + current + " to " + next);
        }
        if (next == PromotionStatus.ACTIVE && !promotion.getEndAt().isAfter(now())) {
            throw new BadRequestException("Không thể kích hoạt khuyến mãi đã kết thúc.");
        }
    }

    private boolean isPubliclyVisible(PromotionEntity p) {
        return p.getStatus() == PromotionStatus.ACTIVE
                && p.getEndAt().isAfter(now())
                && storeAccessService.isPublic(p.getStoreId());
    }

    // ─── Public API ───────────────────────────────────────────────────────────────

    @Override
    public PageResponse<PromotionResponse> getPromotions(Long storeId, boolean mine, Long userId, Pageable pageable) {
        Page<PromotionEntity> page = mine
                ? promotionRepository.findAll(PromotionRepository.ownerListing(
                        storeAccessService.findOwnedStoreIds(userId), storeId), pageable)
                : promotionRepository.findAll(PromotionRepository.publicListing(storeId, now()), pageable);
        return toPage(page);
    }

    @Override
    public PromotionResponse getPromotion(Long promotionId, Long userId) {
        PromotionEntity promotion = requirePromotion(promotionId);
        boolean owner = userId != null && storeAccessService.isOwner(promotion.getStoreId(), userId);
        if (!owner && !isPubliclyVisible(promotion)) {
            throw new ResourceNotFoundException("Không tìm thấy khuyến mãi với id: " + promotionId);
        }
        return toResponse(promotion);
    }

    @Override
    @Transactional
    public PromotionResponse createPromotion(CreatePromotionRequest request, Long userId) {
        storeAccessService.requireOwnedStore(request.storeId(), userId);
        requireValidPeriod(request.startAt(), request.endAt());
        validateRules(request.storeId(), request.rules());

        PromotionEntity promotion = new PromotionEntity();
        promotion.setStoreId(request.storeId());
        promotion.setName(request.name().trim());
        promotion.setStartAt(request.startAt());
        promotion.setEndAt(request.endAt());
        promotion.setStatus(PromotionStatus.DRAFT);
        promotion = promotionRepository.save(promotion);

        return toResponse(promotion, saveRules(promotion.getId(), request.rules()));
    }

    @Override
    @Transactional
    public PromotionResponse updatePromotion(Long promotionId, UpdatePromotionRequest request, Long userId) {
        PromotionEntity promotion = requirePromotion(promotionId);
        storeAccessService.requireOwnedStore(promotion.getStoreId(), userId);

        if (request.name() != null) {
            if (!StringUtils.hasText(request.name())) throw new BadRequestException("Tên khuyến mãi không được để trống.");
            promotion.setName(request.name().trim());
        }
        LocalDateTime startAt = request.startAt() != null ? request.startAt() : promotion.getStartAt();
        LocalDateTime endAt = request.endAt() != null ? request.endAt() : promotion.getEndAt();
        requireValidPeriod(startAt, endAt);
        promotion.setStartAt(startAt);
        promotion.setEndAt(endAt);

        if (request.status() != null) {
            PromotionStatus next = parseStatus(request.status());
            validateStatusTransition(promotion, next);
            promotion.setStatus(next);
        }
        return toResponse(promotionRepository.save(promotion));
    }

    @Override
    @Transactional
    public PromotionResponse replaceRules(Long promotionId, List<PromotionRuleRequest> rules, Long userId) {
        PromotionEntity promotion = requirePromotion(promotionId);
        storeAccessService.requireOwnedStore(promotion.getStoreId(), userId);
        validateRules(promotion.getStoreId(), rules);

        // Xóa rồi ghi lại trong cùng transaction: lỗi giữa chừng thì rollback, không để promotion mất rule.
        ruleRepository.deleteByPromotionId(promotionId);
        return toResponse(promotion, saveRules(promotionId, rules));
    }
}
