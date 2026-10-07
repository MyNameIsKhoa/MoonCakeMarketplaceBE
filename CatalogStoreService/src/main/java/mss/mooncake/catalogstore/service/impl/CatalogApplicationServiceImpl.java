package mss.mooncake.catalogstore.service.impl;
import mss.mooncake.catalogstore.service.CatalogApplicationService;

import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;
import org.springframework.stereotype.Service;

@Service
public class CatalogApplicationServiceImpl implements CatalogApplicationService {
    @Override
    public ProductSnapshotResponse getProductSnapshot(Long productId, Long variantId) {
        throw new UnsupportedOperationException("TODO: implement catalog snapshot lookup");
    }
}
