package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

/**
 * Item của danh sách/search (CA06). minPrice lấy từ biến thể đang bán còn tồn khả dụng. imageUrl lấy từ biến thể ACTIVE.
 */
public record ProductSummaryResponse(Long productId, Long storeId, Long categoryId, String productCode,
                                     String name, String status, BigDecimal minPrice, String imageUrl, boolean purchasable) { }
