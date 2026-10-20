package mss.mooncake.catalogstore.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/** Chi tiết product (CA07–CA09). Public chỉ thấy variant ACTIVE; owner thấy mọi variant. */
public record ProductResponse(Long productId, Long storeId, Long categoryId, String productCode, String name,
                              String description, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
                              List<VariantResponse> variants) { }
