package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;

public record ProductSignalResponse(Long variantId, long soldQuantity, long reviewCount, BigDecimal averageRating) {}
