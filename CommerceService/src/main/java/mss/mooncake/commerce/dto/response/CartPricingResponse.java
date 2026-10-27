package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record CartPricingResponse(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal totalAmount,
                                  List<StorePrice> stores) {
    public record StorePrice(Long storeId, Long supplierId, String storeName, BigDecimal subtotal,
                             BigDecimal discountTotal, BigDecimal totalAmount,
                             List<String> promotions, List<LinePrice> items) { }

    public record LinePrice(Long cartItemId, Long variantId, String productName, String variantName,
                            String sku, String imageUrl, Integer quantity, BigDecimal unitPrice,
                            BigDecimal subtotal, BigDecimal discountAmount, BigDecimal lineTotal) { }
}
