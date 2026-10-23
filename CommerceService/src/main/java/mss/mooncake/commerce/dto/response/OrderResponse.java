package mss.mooncake.commerce.dto.response;
public record OrderResponse(Long orderId, String status, Long sellerOrderId, Long quotationId, Integer quotationVersion) {
    public OrderResponse(Long orderId, String status) { this(orderId, status, null, null, null); }
}
