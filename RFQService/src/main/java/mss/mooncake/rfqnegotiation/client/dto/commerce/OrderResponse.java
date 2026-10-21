package mss.mooncake.rfqnegotiation.client.dto.commerce;
public record OrderResponse(Long orderId, Long sellerOrderId, Long quotationId,
                            Integer quotationVersion, String status) { }
