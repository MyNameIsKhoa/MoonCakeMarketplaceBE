package mss.mooncake.commerce.client;

import java.time.Instant;
import mss.mooncake.commerce.exception.FinanceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.payos.PayOS;
import vn.payos.core.ClientOptions;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.webhooks.Webhook;

@Component
public class PayOsPaymentProvider implements PaymentProvider {
    private final PayOS client;
    private final String returnUrl;
    private final String cancelUrl;

    public PayOsPaymentProvider(@Value("${payos.client-id:}") String clientId,
            @Value("${payos.api-key:}") String apiKey,
            @Value("${payos.checksum-key:}") String checksumKey,
            @Value("${payos.return-url:http://localhost:5173/?finance=return}") String returnUrl,
            @Value("${payos.cancel-url:http://localhost:5173/?finance=cancel}") String cancelUrl) {
        this.client = clientId.isBlank() || apiKey.isBlank() || checksumKey.isBlank() ? null
                : new PayOS(ClientOptions.builder().clientId(clientId).apiKey(apiKey)
                        .checksumKey(checksumKey).maxRetries(0).timeoutMs(10000).build());
        this.returnUrl = returnUrl;
        this.cancelUrl = cancelUrl;
    }

    private PayOS client() {
        if (client == null) {
            throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_NOT_CONFIGURED");
        }
        return client;
    }

    @Override
    public Link create(long orderCode, long amount, Instant expiresAt, String description) {
        var link = client().paymentRequests().create(CreatePaymentLinkRequest.builder()
                .orderCode(orderCode).amount(amount).description(description)
                .returnUrl(returnUrl).cancelUrl(cancelUrl).expiredAt(expiresAt.getEpochSecond()).build());
        return new Link(link.getOrderCode(), link.getAmount(), link.getPaymentLinkId(),
                link.getCheckoutUrl(), link.getQrCode());
    }

    @Override
    public Receipt verify(Webhook webhook) {
        var sdk = client();
        try {
            var data = sdk.webhooks().verify(webhook);
            if (!"00".equals(data.getCode()) || !"00".equals(webhook.getCode())
                    || !Boolean.TRUE.equals(webhook.getSuccess()) || data.getOrderCode() == null
                    || data.getAmount() == null || data.getPaymentLinkId() == null
                    || data.getReference() == null || data.getReference().isBlank()) {
                throw new IllegalArgumentException("Unsuccessful receipt");
            }
            return new Receipt(data.getOrderCode(), data.getAmount(), data.getPaymentLinkId(),
                    data.getReference(), data.getCurrency());
        } catch (RuntimeException ex) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "INVALID_PAYOS_WEBHOOK");
        }
    }

    private Status status(PaymentLink link) {
        // Only complete exact-value links can be collected. Partial/overpayment needs reconciliation.
        String reference = link.getTransactions() == null || link.getTransactions().size() != 1
                ? null : link.getTransactions().getFirst().getReference();
        return new Status(link.getOrderCode(), link.getAmount(), link.getAmountPaid(), link.getId(),
                link.getStatus().name(), reference);
    }

    @Override
    public Status get(long orderCode) {
        return status(client().paymentRequests().get(orderCode));
    }

    @Override
    public Status cancel(long orderCode) {
        return status(client().paymentRequests().cancel(orderCode));
    }
}
