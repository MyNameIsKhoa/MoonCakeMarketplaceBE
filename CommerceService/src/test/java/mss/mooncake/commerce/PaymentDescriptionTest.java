package mss.mooncake.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import mss.mooncake.commerce.util.PaymentDescription;
import org.junit.jupiter.api.Test;

class PaymentDescriptionTest {
    @Test
    void merchantReferenceIsReadableAndFitsPayOsDescription() {
        assertThat(PaymentDescription.customer("MSS-20261024-10005", 10005))
                .isEqualTo("DH MSS-20261024-10005").hasSizeLessThanOrEqualTo(25);
        assertThat(PaymentDescription.seller("MSS-20261024-10005-10006", 10006, "DEPOSIT"))
                .isEqualTo("COC 20261024-10005-10006").hasSizeLessThanOrEqualTo(25);
        assertThat(PaymentDescription.seller("VERY-LONG-SELLER-ORDER-NUMBER", 10006, "FULL"))
                .isEqualTo("DH 10006");
    }
}
