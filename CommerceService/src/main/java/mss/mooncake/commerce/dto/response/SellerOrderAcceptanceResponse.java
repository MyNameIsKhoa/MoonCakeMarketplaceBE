package mss.mooncake.commerce.dto.response;

import java.time.LocalDateTime;

public record SellerOrderAcceptanceResponse(
        Long sellerOrderId,
        Long orderId,
        String status,
        LocalDateTime acceptedAt
) {}
