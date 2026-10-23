package mss.mooncake.commerce.dto.response;
public record OrderResponse(Long orderId, Long sellerOrderId, Long quotationId,
                            Integer quotationVersion, String status) { }
