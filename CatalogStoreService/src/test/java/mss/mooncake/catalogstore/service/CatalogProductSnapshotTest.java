package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StockReservationLineRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import mss.mooncake.catalogstore.service.impl.CatalogApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit test for the single product snapshot used by RFQ/Commerce (IN06). */
@ExtendWith(MockitoExtension.class)
class CatalogProductSnapshotTest {
    @Mock private ProductRepository products;
    @Mock private ProductVariantRepository variants;
    @Mock private StoreRepository stores;
    @Mock private StockReservationLineRepository reservationLines;
    private CatalogApplicationService service;
    private ProductEntity product;
    private ProductVariantEntity variant;
    private StoreEntity store;

    @BeforeEach
    void setUp() {
        service = new CatalogApplicationServiceImpl(variants, products, stores, reservationLines, Clock.systemUTC());
        product = new ProductEntity();
        ReflectionTestUtils.setField(product, "id", 40L);
        product.setStoreId(30L);
        product.setProductCode("PRODUCT-40");
        product.setName("Bánh trung thu");
        product.setStatus(ProductStatus.ACTIVE);
        variant = new ProductVariantEntity();
        ReflectionTestUtils.setField(variant, "id", 50L);
        variant.setProductId(40L);
        variant.setSku("SKU-50");
        variant.setVariantName("Đậu xanh");
        variant.setImageUrl("https://example.test/variant.png");
        variant.setBasePrice(new BigDecimal("200000.00"));
        variant.setStatus(ProductStatus.ACTIVE);
        store = new StoreEntity();
        ReflectionTestUtils.setField(store, "id", 30L);
        store.setSupplierIdRef(20L);
        store.setStatus(StoreStatus.ACTIVE);
        lenient().when(products.findById(40L)).thenReturn(Optional.of(product));
        lenient().when(variants.findByIdAndProductId(50L, 40L)).thenReturn(Optional.of(variant));
        lenient().when(stores.findById(30L)).thenReturn(Optional.of(store));
    }

    @Test
    void includesOwnershipDisplayAndCurrentBasePriceWithLegacyMeaning() {
        var result = service.getProductSnapshot(40L, 50L);

        assertThat(result.storeId()).isEqualTo(30L);
        assertThat(result.supplierId()).isEqualTo(20L);
        assertThat(result.productCode()).isEqualTo("PRODUCT-40");
        assertThat(result.imageUrl()).isEqualTo("https://example.test/variant.png");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.agreedUnitPrice()).isEqualByComparingTo(variant.getBasePrice());
    }

    @Test
    void rejectsVariantOfAnotherProduct() {
        when(variants.findByIdAndProductId(50L, 40L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductSnapshot(40L, 50L)).isInstanceOf(ResourceNotFoundException.class);
        verify(stores, never()).findById(any());
    }

    @Test
    void rejectsMissingProductVariantAndStore() {
        when(products.findById(40L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProductSnapshot(40L, 50L)).isInstanceOf(ResourceNotFoundException.class);
        when(products.findById(40L)).thenReturn(Optional.of(product));
        when(variants.findByIdAndProductId(50L, 40L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProductSnapshot(40L, 50L)).isInstanceOf(ResourceNotFoundException.class);
        when(variants.findByIdAndProductId(50L, 40L)).thenReturn(Optional.of(variant));
        when(stores.findById(30L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProductSnapshot(40L, 50L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void inactiveProductPreventsActiveSnapshot() {
        product.setStatus(ProductStatus.DRAFT);
        assertThat(service.getProductSnapshot(40L, 50L).status()).isEqualTo("DRAFT");
    }

    @Test
    void outOfStockVariantPreventsActiveSnapshot() {
        variant.setStatus(ProductStatus.OUT_OF_STOCK);
        assertThat(service.getProductSnapshot(40L, 50L).status()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void suspendedStorePreventsActiveSnapshot() {
        store.setStatus(StoreStatus.SUSPENDED);
        assertThat(service.getProductSnapshot(40L, 50L).status()).isEqualTo("SUSPENDED");
    }
}
