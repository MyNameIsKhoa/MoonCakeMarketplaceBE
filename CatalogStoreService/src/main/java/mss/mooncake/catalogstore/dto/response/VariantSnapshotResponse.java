package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

/**
 * IN07: snapshot đầy đủ cho Commerce lưu vào ORDER_ITEM. supplierId là Identity supplier ID (store.supplier_id_ref).
 * Trả cả variant/product/store không ACTIVE để caller tự quyết; caller kiểm tra status trước khi bán.
 */
public record VariantSnapshotResponse(Long variantId, Long productId, Long storeId, Long supplierId,
                                      String productCode, String productName, String variantName, String sku,
                                      String imageUrl, BigDecimal basePrice, Integer stockQuantity,
                                      String variantStatus, String productStatus, String storeStatus) { }
