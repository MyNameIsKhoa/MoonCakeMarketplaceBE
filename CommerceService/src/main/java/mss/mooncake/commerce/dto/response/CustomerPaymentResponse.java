package mss.mooncake.commerce.dto.response;

import java.time.Instant;
import java.util.List;

public record CustomerPaymentResponse(long customerOrderId, long totalAmount, long paidAmount,
        Payment payment, List<Allocation> allocations) {
    public record Payment(long id, long orderId, long orderCode, long amount, String status,
            String linkId, String checkoutUrl, String qrCode, Instant expiresAt) { }
    public record Allocation(long paymentId, long sellerOrderId, long amount, String sellerOrderNo) { }
}
