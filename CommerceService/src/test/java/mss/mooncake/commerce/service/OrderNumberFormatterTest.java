package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OrderNumberFormatterTest {
    private final OrderNumberFormatter formatter = new mss.mooncake.commerce.service.impl.OrderNumberFormatterImpl();

    @Test
    void formatsPublicNumbersWithoutUserIdOrRandomDigits() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        assertThat(formatter.customerOrderNo(date, 123)).isEqualTo("MSS-20261021-123");
        assertThat(formatter.sellerOrderNo(date, 123, 456)).isEqualTo("MSS-20261021-123-456");
    }
}
