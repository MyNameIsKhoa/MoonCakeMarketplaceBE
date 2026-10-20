package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;

/**
 * Item của danh sách/search (CA06). minPrice và imageUrl lấy từ các variant ACTIVE;
 * null nếu product chưa có variant ACTIVE.
 */
public record ProductSummaryResponse(Long productId, Long storeId, Long categoryId, String productCode,
                                     String name, String status, BigDecimal minPrice, String imageUrl) { }
