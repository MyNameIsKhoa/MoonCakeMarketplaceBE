package mss.mooncake.catalogstore.service.impl;

import mss.mooncake.catalogstore.service.CatalogApplicationService;

import java.util.List;
import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import org.springframework.stereotype.Service;

@Service
public class CatalogApplicationServiceImpl implements CatalogApplicationService {
    @Override
    public ProductSnapshotResponse getProductSnapshot(Long productId, Long variantId) {
        throw new UnsupportedOperationException("TODO: implement catalog snapshot lookup");
    }

    @Override
    public List<VariantSnapshotResponse> getVariantSnapshots(List<Long> variantIds) {
        throw new UnsupportedOperationException("TODO: implement batch variant snapshot lookup");
    }
}
