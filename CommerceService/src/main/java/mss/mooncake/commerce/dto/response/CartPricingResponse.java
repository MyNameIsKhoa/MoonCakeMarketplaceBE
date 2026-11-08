package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Totals and {@code stores} cover only purchasable lines. Lines whose variant, product or store is no
 * longer for sale are listed in {@code unavailableItems} (never priced) and block checkout until removed.
 */
public record CartPricingResponse(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal totalAmount,
                                  List<StorePrice> stores, List<UnavailableLine> unavailableItems) {
    public CartPricingResponse(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal totalAmount,
                               List<StorePrice> stores) {
        this(subtotal, discountTotal, totalAmount, stores, List.of());
    }

    public record StorePrice(Long storeId, Long supplierId, String storeName, BigDecimal subtotal,
                             BigDecimal discountTotal, BigDecimal totalAmount,
                             List<String> promotions, List<LinePrice> items) { }

    public record LinePrice(Long cartItemId, Long variantId, String productName, String variantName,
                            String sku, String imageUrl, Integer quantity, BigDecimal unitPrice,
                            BigDecimal subtotal, BigDecimal discountAmount, BigDecimal lineTotal) { }

    /** {@code reason} is {@code UNAVAILABLE}: the variant, product or store is not active. */
    public record UnavailableLine(Long cartItemId, Long variantId, String productName, String variantName,
                                  String sku, String imageUrl, Integer quantity, String reason) { }
}
