package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import mss.mooncake.rfqnegotiation.dto.request.CreateRfqRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class RfqRequestContractTest {
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).findAndAddModules().build();

    @Test
    void acceptsIntegerQuantityAndDecimalStringMoney() {
        CreateRfqRequest request = mapper.readValue("""
                {"title":"Company gifts","quantity":100,"budgetAmount":"3500000.00"}
                """, CreateRfqRequest.class);
        assertThat(request.quantity()).isEqualTo(100);
        assertThat(request.budgetAmount()).isEqualByComparingTo(new BigDecimal("3500000.00"));
    }

    @Test
    void rejectsUnknownFieldsAndCallerSuppliedCustomerOrStatus() {
        for (String field : new String[]{"customerId", "status", "unexpectedField"}) {
            assertThatThrownBy(() -> mapper.readValue("{\"title\":\"Company gifts\",\"quantity\":100,\"" + field
                    + "\":\"99\"}", CreateRfqRequest.class)).isInstanceOf(JacksonException.class);
        }
    }

    @Test
    void rejectsFractionalAndStringQuantityInsteadOfCoercing() {
        assertThatThrownBy(() -> mapper.readValue("{\"title\":\"Company gifts\",\"quantity\":1.5}", CreateRfqRequest.class))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"title\":\"Company gifts\",\"quantity\":\"100\"}", CreateRfqRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void rejectsNumericAndScientificMoney() {
        assertThatThrownBy(() -> mapper.readValue("{\"title\":\"Company gifts\",\"quantity\":100,\"budgetAmount\":3500000}",
                CreateRfqRequest.class)).isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"title\":\"Company gifts\",\"quantity\":100,\"budgetAmount\":\"3.5e6\"}",
                CreateRfqRequest.class)).isInstanceOf(JacksonException.class);
    }
}
