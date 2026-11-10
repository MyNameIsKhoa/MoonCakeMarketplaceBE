package mss.mooncake.commerce.repository;

import java.math.BigDecimal;

public interface ProductSignalProjection {
    Long getVariantId();
    Long getSoldQuantity();
    Long getReviewCount();
    BigDecimal getAverageRating();
}
