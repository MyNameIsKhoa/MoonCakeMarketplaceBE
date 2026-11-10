package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

public record VariantResponse(Long variantId, Long productId, String sku, String variantName, String imageUrl,
                              BigDecimal basePrice, Integer stockQuantity, long availableQuantity, String status) { }
