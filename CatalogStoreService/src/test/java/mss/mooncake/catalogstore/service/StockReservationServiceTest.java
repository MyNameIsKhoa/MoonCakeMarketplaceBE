package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.StockReservationRequest;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StockReservationServiceTest {
    @Autowired private StockReservationService stock;
    @Autowired private StoreRepository stores;
    @Autowired private ProductRepository products;
    @Autowired private ProductVariantRepository variants;

    @Test
    void holdsAreIdempotentPreventOversellAndConfirmOnlyOnce() {
        Long variantId = variantWithTenInStock();
        OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20);
        var first = new StockReservationRequest("checkout-101", expiry,
                List.of(new StockReservationRequest.Line(variantId, 7)));
        var second = new StockReservationRequest("checkout-102", expiry,
                List.of(new StockReservationRequest.Line(variantId, 4)));
        assertThat(stock.reserve(first).status()).isEqualTo("HELD");
        assertThat(stock.reserve(first).lines().getFirst().quantity()).isEqualTo(7);
        assertThatThrownBy(() -> stock.reserve(second)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> stock.reserve(new StockReservationRequest("checkout-101", expiry,
                List.of(new StockReservationRequest.Line(variantId, 8)))))
                .isInstanceOf(ConflictException.class);
        assertThat(stock.confirm("checkout-101").status()).isEqualTo("CONFIRMED");
        assertThat(stock.confirm("checkout-101").status()).isEqualTo("CONFIRMED");
        assertThat(variants.findById(variantId).orElseThrow().getStockQuantity()).isEqualTo(3);
        assertThatThrownBy(() -> stock.release("checkout-101")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> stock.reserve(second)).isInstanceOf(ConflictException.class);
        var finalHold = new StockReservationRequest("checkout-103", expiry,
                List.of(new StockReservationRequest.Line(variantId, 3)));
        assertThat(stock.reserve(finalHold).status()).isEqualTo("HELD");
        assertThat(stock.release("checkout-103").status()).isEqualTo("RELEASED");
        assertThat(stock.release("checkout-103").status()).isEqualTo("RELEASED");
        assertThat(stock.reserve(new StockReservationRequest("checkout-104", expiry,
                List.of(new StockReservationRequest.Line(variantId, 3)))).status()).isEqualTo("HELD");
    }

    private Long variantWithTenInStock() {
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(88L);
        store.setStoreCode("STOCK-TEST");
        store.setName("Stock Store");
        store.setSlug("stock-test");
        store.setStatus(StoreStatus.ACTIVE);
        store = stores.saveAndFlush(store);
        ProductEntity product = new ProductEntity();
        product.setStoreId(store.getId());
        product.setCategoryId(1L);
        product.setProductCode("STOCK-PRODUCT");
        product.setName("Product");
        product.setStatus(ProductStatus.ACTIVE);
        product = products.saveAndFlush(product);
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProductId(product.getId());
        variant.setSku("STOCK-SKU");
        variant.setVariantName("Box");
        variant.setBasePrice(new BigDecimal("100"));
        variant.setStockQuantity(10);
        variant.setStatus(ProductStatus.ACTIVE);
        return variants.saveAndFlush(variant).getId();
    }
}
