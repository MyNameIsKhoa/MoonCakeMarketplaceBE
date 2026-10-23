package mss.mooncake.rfqnegotiation.client.dto.commerce;
public record OrderResponse(Long orderId, String status, Long sellerOrderId, Long quotationId, Integer quotationVersion) { }
