package mss.mooncake.commerce.client.dto.catalog;

import java.math.BigDecimal;

public record VariantSnapshotResponse(Long variantId, Long productId, Long storeId, Long supplierId,
                                      String productCode, String productName, String variantName,
                                      String sku, String imageUrl, BigDecimal basePrice,
                                      Integer stockQuantity, String variantStatus,
                                      String productStatus, String storeStatus) { }
