package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogSnapshotServiceTest {
    @Autowired private CatalogApplicationService snapshots;
    @Autowired private StoreRepository stores;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;

    @Test
    void batchSnapshotRetainsRequestedOrderAndRejectsMissingIds() {
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(45L);
        store.setStoreCode("TEST-STORE-SNAPSHOT");
        store.setName("Store at purchase time");
        store.setSlug("test-store-snapshot");
        store.setStatus(StoreStatus.ACTIVE);
        store = stores.saveAndFlush(store);

        ProductEntity product = new ProductEntity();
        product.setStoreId(store.getId());
        product.setCategoryId(1L);
        product.setProductCode("TEST-PRODUCT");
        product.setName("Mooncake");
        product.setStatus(ProductStatus.ACTIVE);
        product = products.saveAndFlush(product);

        ProductVariantEntity first = variant(product.getId(), "TEST-SKU-1");
        ProductVariantEntity second = variant(product.getId(), "TEST-SKU-2");
        var result = snapshots.getVariantSnapshots(List.of(second.getId(), first.getId(), second.getId()));
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().variantId()).isEqualTo(second.getId());
        assertThat(result.getFirst().supplierId()).isEqualTo(45L);
        assertThat(result.getFirst().basePrice()).isEqualByComparingTo("100.00");
        assertThat(snapshots.getProductSnapshot(product.getId(), first.getId()).productName())
                .isEqualTo("Mooncake");
        assertThatThrownBy(() -> snapshots.getVariantSnapshots(List.of(first.getId(), 999999L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private ProductVariantEntity variant(Long productId, String sku) {
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProductId(productId);
        variant.setSku(sku);
        variant.setVariantName("Box");
        variant.setBasePrice(new BigDecimal("100.00"));
        variant.setStockQuantity(10);
        variant.setStatus(ProductStatus.ACTIVE);
        return variants.saveAndFlush(variant);
    }
}
