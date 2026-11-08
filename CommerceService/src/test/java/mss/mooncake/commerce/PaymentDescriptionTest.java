package mss.mooncake.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import mss.mooncake.commerce.util.PaymentDescription;
import org.junit.jupiter.api.Test;

class PaymentDescriptionTest {
    @Test
    void merchantReferenceIsReadableAndFitsPayOsDescription() {
        assertThat(PaymentDescription.customer("MSS-20261024-10005", 10005))
                .isEqualTo("DH MSS-20261024-10005").hasSizeLessThanOrEqualTo(25);
        assertThat(PaymentDescription.seller(12, "DEPOSIT"))
                .matches("COC S12 [0-9]{10} [A-Z]{4}").hasSizeLessThanOrEqualTo(25);
        assertThat(PaymentDescription.seller(123456789, "FULL"))
                .matches("DH S[0-9A-Z]+ [0-9A-Z]+ [A-Z]{4}").hasSizeLessThanOrEqualTo(25);
    }
}
