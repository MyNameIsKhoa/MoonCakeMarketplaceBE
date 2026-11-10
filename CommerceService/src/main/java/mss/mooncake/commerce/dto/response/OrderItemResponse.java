package mss.mooncake.commerce.dto.response;

import java.math.BigDecimal;

/** imageUrl is display-only; imageUrlSnapshot always retains the stored historical value. */
public record OrderItemResponse(Long orderItemId, Long variantIdRef, String productCodeSnapshot,
                                String productNameSnapshot, String skuSnapshot,
                                String variantNameSnapshot, String imageUrlSnapshot,
                                int quantity, BigDecimal unitPrice, BigDecimal discountAmount,
                                BigDecimal subtotal, String imageUrl) { }
