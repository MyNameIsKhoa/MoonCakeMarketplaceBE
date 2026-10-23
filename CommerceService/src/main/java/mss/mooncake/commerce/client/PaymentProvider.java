package mss.mooncake.commerce.client;

import java.time.Instant;
import vn.payos.model.webhooks.Webhook;

public interface PaymentProvider {
    record Link(long orderCode, long amount, String id, String checkoutUrl, String qrCode) { }
    record Receipt(long orderCode, long amount, String linkId, String reference, String currency) { }
    record Status(long orderCode, long amount, long paid, String linkId, String status, String reference) { }

    Link create(long orderCode, long amount, Instant expiresAt);
    Receipt verify(Webhook webhook);
    Status get(long orderCode);
    Status cancel(long orderCode);
}
