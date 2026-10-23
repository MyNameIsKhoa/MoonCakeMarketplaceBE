package mss.mooncake.commerce.client.dto.catalog;

import java.util.List;

public record PromotionCalculationRequest(Long storeId, List<Line> lines) {
    public record Line(Long variantId, Integer quantity) { }
}
