package mss.mooncake.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mss.mooncake.commerce.client.PayOsPaymentProvider;
import org.junit.jupiter.api.Test;
import vn.payos.PayOS;
import vn.payos.model.webhooks.Webhook;
import vn.payos.model.webhooks.WebhookData;

class PayOsPaymentProviderTest {
    private static final String CHECKSUM = "test-checksum-key-no-live-credentials";

    @Test
    void sdkRejectsForgedSignatureWithoutAnyNetworkCall() {
        var provider = new PayOsPaymentProvider("test-client", "test-api", CHECKSUM,
                "https://example.test/return", "https://example.test/cancel");
        var data = WebhookData.builder().orderCode(1000L).amount(10001L).paymentLinkId("link")
                .reference("reference").currency("VND").code("00").desc("success")
                .description("MSS test").accountNumber("123").transactionDateTime("2030-10-21 10:00:00").build();
        var body = Webhook.builder().code("00").desc("success").success(true).data(data).signature("fake-signature").build();
        assertThatThrownBy(() -> provider.verify(body)).hasMessage("INVALID_PAYOS_WEBHOOK");
    }

    @Test
    void sdkVerifiesSignatureAndDetectsTamperedAmount() {
        var provider = new PayOsPaymentProvider("test-client", "test-api", CHECKSUM,
                "https://example.test/return", "https://example.test/cancel");
        var sdk = new PayOS("test-client", "test-api", CHECKSUM);
        var data = WebhookData.builder().orderCode(1000L).amount(10001L).paymentLinkId("link")
                .reference("reference").currency("VND").code("00").desc("success")
                .description("MSS test").accountNumber("123").transactionDateTime("2030-10-21 10:00:00").build();
        var body = Webhook.builder().code("00").desc("success").success(true).data(data)
                .signature(sdk.getCrypto().createSignatureFromObj(data, CHECKSUM)).build();
        assertThat(provider.verify(body).amount()).isEqualTo(10001);
        data.setAmount(1L);
        assertThatThrownBy(() -> provider.verify(body)).hasMessage("INVALID_PAYOS_WEBHOOK");
    }
}
