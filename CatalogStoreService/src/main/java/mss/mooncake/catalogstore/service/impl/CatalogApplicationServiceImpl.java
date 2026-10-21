package mss.mooncake.catalogstore.service.impl;

import mss.mooncake.catalogstore.service.CatalogApplicationService;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogApplicationServiceImpl implements CatalogApplicationService {
    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final StoreRepository stores;

    public CatalogApplicationServiceImpl(ProductVariantRepository variants, ProductRepository products,
                                         StoreRepository stores) {
        this.variants = variants;
        this.products = products;
        this.stores = stores;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSnapshotResponse getProductSnapshot(Long productId, Long variantId) {
        ProductVariantEntity variant = variants.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant does not belong to product"));
        ProductEntity product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
        return new ProductSnapshotResponse(productId, variantId, product.getName(),
                variant.getVariantName(), variant.getSku(), variant.getBasePrice());
    }

    @Override
    @Transactional(readOnly = true)
    public List<VariantSnapshotResponse> getVariantSnapshots(List<Long> variantIds) {
        List<Long> uniqueIds = new LinkedHashSet<>(variantIds).stream().toList();
        Map<Long, ProductVariantEntity> byId = variants.findByIdIn(uniqueIds).stream()
                .collect(Collectors.toMap(ProductVariantEntity::getId, value -> value));
        List<Long> missing = uniqueIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) throw new ResourceNotFoundException("Variants not found: " + missing);
        Map<Long, ProductEntity> productById = products.findAllById(byId.values().stream()
                .map(ProductVariantEntity::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ProductEntity::getId, value -> value));
        Map<Long, StoreEntity> storeById = stores.findAllById(productById.values().stream()
                .map(ProductEntity::getStoreId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(StoreEntity::getId, value -> value));
        return uniqueIds.stream().map(id -> {
            ProductVariantEntity variant = byId.get(id);
            ProductEntity product = productById.get(variant.getProductId());
            if (product == null) throw new ResourceNotFoundException("Product not found for variant " + id);
            StoreEntity store = storeById.get(product.getStoreId());
            if (store == null) throw new ResourceNotFoundException("Store not found for variant " + id);
            return new VariantSnapshotResponse(id, product.getId(), store.getId(),
                    store.getSupplierIdRef(), product.getProductCode(), product.getName(),
                    variant.getVariantName(), variant.getSku(), variant.getImageUrl(),
                    variant.getBasePrice(), variant.getStockQuantity(), variant.getStatus().name(),
                    product.getStatus().name(), store.getStatus().name());
        }).toList();
    }
}
