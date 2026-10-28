package mss.mooncake.commerce.client;

import java.time.Instant;
import vn.payos.exception.APIException;
import vn.payos.model.webhooks.Webhook;

public interface PaymentProvider {
    record Link(long orderCode, long amount, String id, String checkoutUrl, String qrCode) { }
    record Receipt(long orderCode, long amount, String linkId, String reference, String currency) { }
    record Status(long orderCode, long amount, long paid, String linkId, String status, String reference) { }

    Link create(long orderCode, long amount, Instant expiresAt, String description);
    Receipt verify(Webhook webhook);
    Status get(long orderCode);
    Status cancel(long orderCode);

    /** Log text for a failed provider call: the PayOS HTTP status, code and description when the SDK has them. */
    static String describe(RuntimeException ex) {
        if (ex instanceof APIException api) {
            return "HTTP " + api.getStatusCode().map(String::valueOf).orElse("?")
                    + " code " + api.getErrorCode().orElse("?") + " " + api.getErrorDesc().orElse(String.valueOf(ex.getMessage()));
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
}
