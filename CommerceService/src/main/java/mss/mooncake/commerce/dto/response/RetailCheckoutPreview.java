package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record RetailCheckoutPreview(String quote, List<Group> groups, BigDecimal subtotal,
                                    BigDecimal discountTotal, BigDecimal shippingTotal, BigDecimal grandTotal) {
    public record Group(CartPricingResponse.StorePrice price, BigDecimal shippingFee) { }
}
