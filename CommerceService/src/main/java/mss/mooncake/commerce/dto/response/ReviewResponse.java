package mss.mooncake.commerce.dto.response;

import java.time.LocalDateTime;

public record ReviewResponse(
        Long reviewId,
        Long orderItemId,
        Long customerId,
        Integer rating,
        String comment,
        LocalDateTime createdAt,
        String productNameSnapshot,
        String variantNameSnapshot,
        String displayName
) {
    public ReviewResponse(
            Long reviewId,
            Long orderItemId,
            Long customerId,
            Integer rating,
            String comment,
            LocalDateTime createdAt
    ) {
        this(reviewId, orderItemId, customerId, rating, comment, createdAt, null, null, null);
    }
}
