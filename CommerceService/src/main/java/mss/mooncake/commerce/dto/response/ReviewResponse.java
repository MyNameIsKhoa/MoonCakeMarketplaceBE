package mss.mooncake.commerce.dto.response;

import java.time.LocalDateTime;

public record ReviewResponse(
        Long reviewId,
        Long orderItemId,
        Long customerId,
        Integer rating,
        String comment,
        LocalDateTime createdAt
) {}
