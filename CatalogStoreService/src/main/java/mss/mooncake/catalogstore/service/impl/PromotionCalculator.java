package mss.mooncake.catalogstore.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import mss.mooncake.catalogstore.enums.PromotionType;
import mss.mooncake.catalogstore.exception.ConflictException;

final class PromotionCalculator {
    PromotionCalculationResult calculate(PromotionCalculationRequest request,
            List<VariantSnapshotResponse> snapshots, List<PromotionEntity> promotions,
            List<PromotionRuleEntity> rules, LocalDateTime now) {
        Map<Long, VariantSnapshotResponse> byId = new HashMap<>();
        snapshots.forEach(snapshot -> byId.put(snapshot.variantId(), snapshot));
        Map<Long, Line> lines = new LinkedHashMap<>();
        for (var item : request.lines()) {
            var snapshot = byId.get(item.variantId());
            if (snapshot == null || snapshot.basePrice() == null || snapshot.basePrice().signum() < 0) {
                throw new ConflictException("Catalog price snapshot is incomplete");
            }
            lines.put(item.variantId(), new Line(item.variantId(), item.quantity(), snapshot.basePrice()));
        }
        Map<Long, PromotionEntity> byPromotion = new HashMap<>();
        promotions.forEach(promotion -> byPromotion.put(promotion.getId(), promotion));
        List<PromotionRuleEntity> orderedRules = rules.stream()
                .sorted(Comparator.comparing(PromotionRuleEntity::getId)).toList();
        orderedRules.forEach(this::validate);
        Map<Long, Applied> applied = new LinkedHashMap<>();

        // A reward line receives only its best BUY_X_GET_Y rule; ties use the lower rule ID.
        Map<Long, RewardCandidate> bestRewards = new HashMap<>();
        for (var rule : orderedRules) {
            if (rule.getRuleType() != PromotionType.BUY_X_GET_Y) continue;
            Line trigger = lines.get(rule.getTargetVariantId());
            Line reward = lines.get(rule.getRewardVariantId());
            if (trigger == null || reward == null) continue;
            long groupSize = rule.getTargetVariantId().equals(rule.getRewardVariantId())
                    ? (long) rule.getMinQuantity() + rule.getRewardQuantity() : rule.getMinQuantity();
            long eligible = trigger.quantity / groupSize * rule.getRewardQuantity();
            int freeQuantity = (int) Math.min(eligible, reward.quantity);
            BigDecimal amount = reward.unitPrice.multiply(BigDecimal.valueOf(freeQuantity));
            RewardCandidate previous = bestRewards.get(reward.variantId);
            if (amount.signum() > 0 && (previous == null || amount.compareTo(previous.amount) > 0)) {
                bestRewards.put(reward.variantId, new RewardCandidate(rule, amount, freeQuantity));
            }
        }
        for (var line : lines.values()) {
            RewardCandidate winner = bestRewards.get(line.variantId);
            if (winner != null) apply(line, winner.rule, winner.amount, winner.freeQuantity, applied);
        }

        // Targeted fixed rules get their target first; store-wide rules share the remaining capacity.
        for (var rule : orderedRules) {
            if (rule.getRuleType() != PromotionType.FIXED_DISCOUNT || rule.getTargetVariantId() == null) continue;
            Line line = lines.get(rule.getTargetVariantId());
            if (line != null && line.quantity >= minimumQuantity(rule)) {
                apply(line, rule, rule.getDiscountAmount(), null, applied);
            }
        }
        for (var rule : orderedRules) {
            if (rule.getRuleType() == PromotionType.FIXED_DISCOUNT && rule.getTargetVariantId() == null
                    && lines.values().stream().mapToLong(line -> line.quantity).sum() >= minimumQuantity(rule)) {
                allocateStoreFixed(new ArrayList<>(lines.values()), rule, applied);
            }
        }

        // Every percentage uses the same post-fixed basis; rates add, capped at 100% per line.
        for (var line : lines.values()) {
            BigDecimal basis = line.remaining();
            for (var rule : orderedRules) {
                if (rule.getRuleType() != PromotionType.PERCENT_DISCOUNT
                        || rule.getTargetVariantId() != null && !rule.getTargetVariantId().equals(line.variantId)
                        || line.quantity < minimumQuantity(rule)) {
                    continue;
                }
                BigDecimal amount = basis.multiply(rule.getDiscountPercent()).movePointLeft(2);
                apply(line, rule, amount, null, applied);
            }
        }

        BigDecimal subtotal = lines.values().stream().map(line -> line.subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = lines.values().stream().map(line -> line.discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // shortcut: keep exact line decimals here; allocate cents when ORDER_ITEM DECIMAL(18,2) is persisted.
        BigDecimal finalAmount = subtotal.subtract(discount).setScale(0, RoundingMode.CEILING);
        List<PromotionCalculationResult.LineResult> results = lines.values().stream()
                .map(line -> new PromotionCalculationResult.LineResult(line.variantId, line.quantity,
                        line.unitPrice, line.subtotal, line.discount, line.remaining(),
                        List.copyOf(line.applications))).toList();
        List<PromotionCalculationResult.AppliedPromotion> appliedResults = applied.values().stream()
                .map(entry -> {
                    var promotion = byPromotion.get(entry.rule.getPromotionId());
                    if (promotion == null) throw new ConflictException("Promotion rule has no active promotion");
                    return new PromotionCalculationResult.AppliedPromotion(promotion.getId(), promotion.getName(),
                            entry.rule.getId(), entry.rule.getRuleType().name(), entry.amount,
                            entry.rule.getRewardVariantId(), entry.rewardQuantity);
                }).toList();
        return new PromotionCalculationResult(request.storeId(), now, subtotal, discount, finalAmount,
                results, appliedResults);
    }

    private void allocateStoreFixed(List<Line> all, PromotionRuleEntity rule, Map<Long, Applied> applied) {
        BigDecimal left = rule.getDiscountAmount().min(all.stream().map(Line::remaining)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        while (left.signum() > 0) {
            List<Line> eligible = all.stream().filter(line -> line.remaining().signum() > 0).toList();
            if (eligible.isEmpty()) throw new ConflictException("Cannot allocate store discount");
            BigDecimal weight = eligible.stream().map(line -> line.subtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal distributed = BigDecimal.ZERO;
            for (int i = 0; i < eligible.size(); i++) {
                Line line = eligible.get(i);
                BigDecimal share = i == eligible.size() - 1 ? left.subtract(distributed)
                        : left.multiply(line.subtotal).divide(weight, 2, RoundingMode.DOWN);
                distributed = distributed.add(apply(line, rule, share, null, applied));
            }
            if (distributed.signum() == 0) throw new ConflictException("Cannot allocate store discount");
            left = left.subtract(distributed);
        }
    }

    private BigDecimal apply(Line line, PromotionRuleEntity rule, BigDecimal amount, Integer rewardQuantity,
            Map<Long, Applied> applied) {
        BigDecimal appliedAmount = amount.min(line.remaining());
        if (appliedAmount.signum() <= 0) return BigDecimal.ZERO;
        line.discount = line.discount.add(appliedAmount);
        line.applications.add(new PromotionCalculationResult.RuleApplication(
                rule.getPromotionId(), rule.getId(), appliedAmount));
        Applied total = applied.computeIfAbsent(rule.getId(), ignored -> new Applied(rule));
        total.amount = total.amount.add(appliedAmount);
        if (rewardQuantity != null) total.rewardQuantity = rewardQuantity;
        return appliedAmount;
    }

    private void validate(PromotionRuleEntity rule) {
        if (rule.getRuleType() == null) throw new ConflictException("Active promotion rule has no type");
        if (rule.getMinQuantity() != null && rule.getMinQuantity() <= 0)
            throw new ConflictException("Invalid minimum quantity");
        switch (rule.getRuleType()) {
            case FIXED_DISCOUNT -> {
                if (rule.getDiscountAmount() == null || rule.getDiscountAmount().signum() < 0)
                    throw new ConflictException("Invalid fixed discount rule");
            }
            case PERCENT_DISCOUNT -> {
                if (rule.getDiscountPercent() == null || rule.getDiscountPercent().signum() < 0
                        || rule.getDiscountPercent().compareTo(BigDecimal.valueOf(100)) > 0)
                    throw new ConflictException("Invalid percentage discount rule");
            }
            case BUY_X_GET_Y -> {
                if (rule.getTargetVariantId() == null || rule.getRewardVariantId() == null
                        || rule.getMinQuantity() == null || rule.getMinQuantity() <= 0
                        || rule.getRewardQuantity() == null || rule.getRewardQuantity() <= 0)
                    throw new ConflictException("Invalid buy-X-get-Y rule");
            }
        }
    }

    private int minimumQuantity(PromotionRuleEntity rule) {
        return rule.getMinQuantity() == null ? 1 : rule.getMinQuantity();
    }

    private static final class Line {
        final Long variantId;
        final int quantity;
        final BigDecimal unitPrice;
        final BigDecimal subtotal;
        BigDecimal discount = BigDecimal.ZERO;
        final List<PromotionCalculationResult.RuleApplication> applications = new ArrayList<>();

        Line(Long variantId, int quantity, BigDecimal unitPrice) {
            this.variantId = variantId;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }

        BigDecimal remaining() { return subtotal.subtract(discount); }
    }

    private record RewardCandidate(PromotionRuleEntity rule, BigDecimal amount, int freeQuantity) { }

    private static final class Applied {
        final PromotionRuleEntity rule;
        BigDecimal amount = BigDecimal.ZERO;
        Integer rewardQuantity;

        Applied(PromotionRuleEntity rule) { this.rule = rule; }
    }
}
