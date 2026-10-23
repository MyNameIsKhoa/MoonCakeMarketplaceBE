package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.ReviseQuotationRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class QuotationRequestContractTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).findAndAddModules().build();
    private static final String CREATE = """
            {"storeId":"30","depositPercent":"30.00","validUntil":"2026-12-01T00:00:00Z",
             "proposedDeliveryDate":"2026-12-10","shippingFee":"10000.00",
             "items":[{"itemNameSnapshot":"Hộp bánh","quantity":10,"unitPrice":"100.00"}]}
            """;

    @Test
    void acceptsDecimalStringsIntegerQuantityAndUtcTimestamp() {
        var request = mapper.readValue(CREATE, CreateQuotationRequest.class);
        assertThat(request.storeId()).isEqualTo(30L);
        assertThat(request.depositPercent()).isEqualByComparingTo("30.00");
        assertThat(request.items().getFirst().quantity()).isEqualTo(10);
        assertThat(request.validUntil().getOffset().getTotalSeconds()).isZero();
    }

    @Test
    void rejectsNumericIdsAndMoneyAndFractionalQuantities() {
        for (String invalid : new String[]{
                CREATE.replace("\"storeId\":\"30\"", "\"storeId\":30"),
                CREATE.replace("\"depositPercent\":\"30.00\"", "\"depositPercent\":30"),
                CREATE.replace("\"unitPrice\":\"100.00\"", "\"unitPrice\":100"),
                CREATE.replace("\"quantity\":10", "\"quantity\":10.5"),
                CREATE.replace("\"quantity\":10", "\"quantity\":\"10\""),
                CREATE.replace("\"unitPrice\":\"100.00\"", "\"unitPrice\":\"1e2\"")}) {
            assertThatThrownBy(() -> mapper.readValue(invalid, CreateQuotationRequest.class)).isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void rejectsCallerSuppliedOwnerStatusVersionTotalsAndUnknownItemFields() {
        for (String field : new String[]{"supplierId", "customerId", "status", "version", "totalAmount", "unexpectedField"}) {
            String invalid = CREATE.replace("\"storeId\":\"30\"", "\"storeId\":\"30\",\"" + field + "\":\"999\"");
            assertThatThrownBy(() -> mapper.readValue(invalid, CreateQuotationRequest.class)).isInstanceOf(JacksonException.class);
        }
        String invalidItem = CREATE.replace("\"quantity\":10", "\"quantity\":10,\"subtotal\":\"1\"");
        assertThatThrownBy(() -> mapper.readValue(invalidItem, CreateQuotationRequest.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void rejectsRevisionWithStringVersionAndStoreChanges() {
        String revision = CREATE.replace("\"storeId\":\"30\",", "\"expectedVersion\":1,");
        assertThat(mapper.readValue(revision, ReviseQuotationRequest.class).expectedVersion()).isEqualTo(1);
        assertThatThrownBy(() -> mapper.readValue(revision.replace("\"expectedVersion\":1", "\"expectedVersion\":\"1\""),
                ReviseQuotationRequest.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue(revision.replace("\"expectedVersion\":1", "\"expectedVersion\":1,\"storeId\":\"50\""),
                ReviseQuotationRequest.class)).isInstanceOf(JacksonException.class);
    }
}
