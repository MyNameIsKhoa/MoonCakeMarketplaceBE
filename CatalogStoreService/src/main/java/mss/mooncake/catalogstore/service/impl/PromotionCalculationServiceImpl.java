package mss.mooncake.catalogstore.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.repository.PromotionRepository;
import mss.mooncake.catalogstore.repository.PromotionRuleRepository;
import mss.mooncake.catalogstore.service.CatalogApplicationService;
import mss.mooncake.catalogstore.service.PromotionCalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionCalculationServiceImpl implements PromotionCalculationService {
    private final CatalogApplicationService catalog;
    private final PromotionRepository promotions;
    private final PromotionRuleRepository rules;
    private final Clock clock;
    private final PromotionCalculator calculator = new PromotionCalculator();

    public PromotionCalculationServiceImpl(CatalogApplicationService catalog, PromotionRepository promotions,
            PromotionRuleRepository rules, Clock clock) {
        this.catalog = catalog;
        this.promotions = promotions;
        this.rules = rules;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionCalculationResult calculate(PromotionCalculationRequest request) {
        if (request == null || request.storeId() == null || request.storeId() <= 0
                || request.lines() == null || request.lines().isEmpty() || request.lines().size() > 100) {
            throw new BadRequestException("Invalid promotion basket");
        }
        var quantities = new LinkedHashMap<Long, Integer>();
        for (var line : request.lines()) {
            if (line == null || line.variantId() == null || line.variantId() <= 0
                    || line.quantity() == null || line.quantity() <= 0) {
                throw new BadRequestException("Invalid variant in promotion basket");
            }
            try {
                quantities.merge(line.variantId(), line.quantity(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new BadRequestException("Quantity exceeds supported range");
            }
        }
        var combined = new PromotionCalculationRequest(request.storeId(), quantities.entrySet().stream()
                .map(entry -> new PromotionCalculationRequest.Line(entry.getKey(), entry.getValue())).toList());
        var snapshots = catalog.getVariantSnapshots(quantities.keySet().stream().toList());
        for (var snapshot : snapshots) {
            if (!request.storeId().equals(snapshot.storeId())) {
                throw new BadRequestException("Variant belongs to another store");
            }
            if (!"ACTIVE".equals(snapshot.variantStatus()) || !"ACTIVE".equals(snapshot.productStatus())
                    || !"ACTIVE".equals(snapshot.storeStatus())) {
                throw new ConflictException("Variant, product or store is not active");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        var effective = promotions.findEffective(request.storeId(), PromotionStatus.ACTIVE, now);
        var activeRules = effective.isEmpty() ? List.<mss.mooncake.catalogstore.entity.PromotionRuleEntity>of()
                : rules.findByPromotionIdIn(effective.stream().map(p -> p.getId()).toList());
        return calculator.calculate(combined, snapshots, effective, activeRules, now);
    }
}
