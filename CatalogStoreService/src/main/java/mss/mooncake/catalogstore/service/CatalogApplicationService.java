package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;

public interface CatalogApplicationService {
    ProductSnapshotResponse getProductSnapshot(Long productId, Long variantId);
    // TODO: Add catalog CRUD and promotion calculation use cases.
}
