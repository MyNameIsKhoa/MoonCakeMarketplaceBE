package mss.mooncake.catalogstore.service.impl;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import mss.mooncake.catalogstore.dto.response.ProductSnapshotResponse;
import mss.mooncake.catalogstore.dto.response.VariantSnapshotResponse;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import mss.mooncake.catalogstore.service.CatalogApplicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshot cho Commerce/RFQ. Trả dữ liệu ở mọi trạng thái (kèm status ở IN07) để caller tự quyết;
 * không lọc theo quyền public vì chỉ service đã xác thực mới gọi được /internal/**.
 */
@Service
@Transactional(readOnly = true)
public class CatalogApplicationServiceImpl implements CatalogApplicationService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StoreRepository storeRepository;

    public CatalogApplicationServiceImpl(ProductRepository productRepository,
                                         ProductVariantRepository variantRepository,
                                         StoreRepository storeRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.storeRepository = storeRepository;
    }

    @Override
    public ProductSnapshotResponse getProductSnapshot(Long productId, Long variantId) {
        ProductVariantEntity variant = variantRepository.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy biến thể " + variantId + " của sản phẩm " + productId));
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với id: " + productId));
        // agreedUnitPrice giữ tên field cũ vì Commerce/RFQ đã mirror DTO; giá trị là basePrice hiện tại.
        return new ProductSnapshotResponse(product.getId(), variant.getId(), product.getName(),
                variant.getVariantName(), variant.getSku(), variant.getBasePrice());
    }

    @Override
    public List<VariantSnapshotResponse> getVariantSnapshots(List<Long> variantIds) {
        // Gộp ID trùng, giữ thứ tự đầu vào.
        List<Long> ids = List.copyOf(new LinkedHashSet<>(variantIds));

        Map<Long, ProductVariantEntity> variants = variantRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(ProductVariantEntity::getId, Function.identity()));
        List<Long> missing = ids.stream().filter(id -> !variants.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy biến thể: " + missing);
        }

        // Load product và store theo lô (3 query cho cả batch, không N+1).
        Map<Long, ProductEntity> products = productRepository.findAllById(
                        variants.values().stream().map(ProductVariantEntity::getProductId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
        Map<Long, StoreEntity> stores = storeRepository.findAllById(
                        products.values().stream().map(ProductEntity::getStoreId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(StoreEntity::getId, Function.identity()));

        return ids.stream().map(id -> {
            ProductVariantEntity v = variants.get(id);
            ProductEntity p = products.get(v.getProductId());
            StoreEntity s = stores.get(p.getStoreId());
            return new VariantSnapshotResponse(v.getId(), p.getId(), s.getId(), s.getSupplierIdRef(),
                    p.getProductCode(), p.getName(), v.getVariantName(), v.getSku(), v.getImageUrl(),
                    v.getBasePrice(), v.getStockQuantity(),
                    v.getStatus().name(), p.getStatus().name(), s.getStatus().name());
        }).toList();
    }
}
