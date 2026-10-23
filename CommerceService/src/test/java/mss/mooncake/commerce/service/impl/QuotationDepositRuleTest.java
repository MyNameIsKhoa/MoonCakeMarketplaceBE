package mss.mooncake.commerce.service.impl;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
// TV5 takes a deposit only strictly between 0 and the total; anything else is one FULL payment (null deposit).
class QuotationDepositRuleTest {
    @Test void roundsHalfUpToWholeDong() {
        assertThat(OrderApplicationServiceImpl.agreedDeposit(new BigDecimal("1005.00"),new BigDecimal("30.00"))).isEqualTo(302L);
        assertThat(OrderApplicationServiceImpl.agreedDeposit(new BigDecimal("1005.00"),new BigDecimal("33.33"))).isEqualTo(335L);
    }
    @Test void zeroOrWholeTotalBecomesFullPayment() {
        assertThat(OrderApplicationServiceImpl.agreedDeposit(new BigDecimal("1005.00"),new BigDecimal("0.00"))).isNull();
        assertThat(OrderApplicationServiceImpl.agreedDeposit(new BigDecimal("1005.00"),new BigDecimal("100.00"))).isNull();
        // Rounding can reach the bounds: 99.99% of 1005 = 1004.8995 -> 1005, 30% of 1 = 0.3 -> 0.
        assertThat(OrderApplicationServiceImpl.agreedDeposit(new BigDecimal("1005.00"),new BigDecimal("99.99"))).isNull();
        assertThat(OrderApplicationServiceImpl.agreedDeposit(new BigDecimal("1.00"),new BigDecimal("30.00"))).isNull();
    }
}
