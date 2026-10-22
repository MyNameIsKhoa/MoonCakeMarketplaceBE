package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

/**
 * IN07: snapshot đầy đủ cho Commerce lưu vào ORDER_ITEM. supplierId là Identity supplier ID (store.supplier_id_ref).
 * Trả cả variant/product/store không ACTIVE để caller tự quyết; caller kiểm tra status trước khi bán.
 * stockQuantity là tồn thực tế (on-hand); availableQuantity = stockQuantity trừ lượng đang giữ bởi IN12 (HELD, chưa
 * hết hạn), không âm — dùng field này để quyết định còn bán được hay không.
 */
public record VariantSnapshotResponse(Long variantId, Long productId, Long storeId, Long supplierId,
                                      String productCode, String productName, String variantName, String sku,
                                      String imageUrl, BigDecimal basePrice, Integer stockQuantity,
                                      Integer availableQuantity,
                                      String variantStatus, String productStatus, String storeStatus) { }
