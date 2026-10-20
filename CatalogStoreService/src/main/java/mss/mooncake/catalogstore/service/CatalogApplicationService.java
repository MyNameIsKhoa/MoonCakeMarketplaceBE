package mss.mooncake.catalogstore.service;

import java.util.List;
import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;

/**
 * Snapshot contract cho service khác (Commerce, RFQ). Chỉ gọi qua /internal/v1
 * với service token.
 */
public interface CatalogApplicationService {

    /**
     * IN06 — snapshot một variant; variant không thuộc productId trả 404.
     * Giữ nguyên field hiện có (agreedUnitPrice = basePrice) vì Commerce/RFQ đã
     * mirror DTO này.
     */
    ProductSnapshotResponse getProductSnapshot(Long productId, Long variantId);

    /**
     * IN07 — snapshot theo lô, giữ thứ tự variantIds đầu vào. ID trùng được gộp;
     * có ID không tồn tại thì trả 404 kèm danh sách ID thiếu.
     */
    List<VariantSnapshotResponse> getVariantSnapshots(List<Long> variantIds);
}
