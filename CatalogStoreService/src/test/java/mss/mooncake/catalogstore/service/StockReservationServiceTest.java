package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.StockReservationRequest;
import mss.mooncake.catalogstore.dto.request.StockRestoreRequest;
import mss.mooncake.catalogstore.dto.response.StockReservationResponse;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
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

    // --- Per-line restore of a partially cancelled checkout ---

    private static StockReservationRequest.Line line(Long variantId, int quantity) {
        return new StockReservationRequest.Line(variantId, quantity);
    }

    private int onHand(Long variantId) {
        return variants.findById(variantId).orElseThrow().getStockQuantity();
    }

    /** Confirmed checkout of 6 units of the first variant and 4 of the second (10 in stock each). */
    private void confirmedCheckout(String key, Long first, Long second) {
        stock.reserve(new StockReservationRequest(key, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20),
                List.of(line(first, 6), line(second, 4))));
        stock.confirm(key);
    }

    @Test
    void restoringLinesReturnsExactlyThoseUnitsOncePerRestoreKey() {
        Long first = variantWithTenInStock("RL1");
        Long second = variantWithTenInStock("RL2");
        confirmedCheckout("checkout-201", first, second);
        var restore = new StockRestoreRequest("checkout-201-so1", List.of(line(first, 2)));

        assertThat(stock.restoreLines("checkout-201", restore).lines())
                .containsExactly(new StockReservationResponse.Line(first, 2));
        assertThat(stock.restoreLines("checkout-201", restore).restoreKey()).isEqualTo("checkout-201-so1");

        // The retry restores nothing a second time; the sibling seller order's units stay sold.
        assertThat(onHand(first)).isEqualTo(6);
        assertThat(onHand(second)).isEqualTo(6);
        assertThatThrownBy(() -> stock.restoreLines("checkout-201",
                new StockRestoreRequest("checkout-201-so1", List.of(line(first, 3)))))
                .isInstanceOf(ConflictException.class);
        // Only 4 confirmed units of the first variant are still sold.
        assertThatThrownBy(() -> stock.restoreLines("checkout-201",
                new StockRestoreRequest("checkout-201-so2", List.of(line(first, 5)))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> stock.restoreLines("checkout-201",
                new StockRestoreRequest("checkout-201-so3", List.of(line(variantWithTenInStock("RL3"), 1)))))
                .isInstanceOf(BadRequestException.class);
        assertThat(onHand(first)).isEqualTo(6);
    }

    @Test
    void wholeCancellationAfterAPartialRestoreReturnsOnlyTheRest() {
        Long first = variantWithTenInStock("WC1");
        Long second = variantWithTenInStock("WC2");
        confirmedCheckout("checkout-202", first, second);
        stock.restoreLines("checkout-202", new StockRestoreRequest("checkout-202-so1", List.of(line(first, 2))));

        assertThat(stock.cancelConfirmed("checkout-202").status()).isEqualTo("CANCELLED");

        assertThat(onHand(first)).isEqualTo(10);
        assertThat(onHand(second)).isEqualTo(10);
        // A per-line restore that arrives after the whole checkout was returned changes nothing.
        assertThat(stock.restoreLines("checkout-202", new StockRestoreRequest("checkout-202-so2",
                List.of(line(second, 4)))).reservationStatus()).isEqualTo("CANCELLED");
        assertThat(onHand(second)).isEqualTo(10);
        assertThat(stock.cancelConfirmed("checkout-202").status()).isEqualTo("CANCELLED");
        assertThat(onHand(first)).isEqualTo(10);
    }

    @Test
    void onlyConfirmedStockCanBeRestoredPerLine() {
        Long variantId = variantWithTenInStock("HL1");
        stock.reserve(new StockReservationRequest("checkout-203", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(20),
                List.of(line(variantId, 3))));

        assertThatThrownBy(() -> stock.restoreLines("checkout-203",
                new StockRestoreRequest("checkout-203-so1", List.of(line(variantId, 1)))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> stock.restoreLines("checkout-missing",
                new StockRestoreRequest("checkout-missing-so1", List.of(line(variantId, 1)))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(onHand(variantId)).isEqualTo(10);
    }

    private Long variantWithTenInStock() {
        return variantWithTenInStock("");
    }

    private Long variantWithTenInStock(String suffix) {
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(88L);
        store.setStoreCode("STOCK-TEST" + suffix);
        store.setName("Stock Store");
        store.setSlug("stock-test" + suffix.toLowerCase());
        store.setStatus(StoreStatus.ACTIVE);
        store = stores.saveAndFlush(store);
        ProductEntity product = new ProductEntity();
        product.setStoreId(store.getId());
        product.setCategoryId(1L);
        product.setProductCode("STOCK-PRODUCT" + suffix);
        product.setName("Product");
        product.setStatus(ProductStatus.ACTIVE);
        product = products.saveAndFlush(product);
        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProductId(product.getId());
        variant.setSku("STOCK-SKU" + suffix);
        variant.setVariantName("Box");
        variant.setBasePrice(new BigDecimal("100"));
        variant.setStockQuantity(10);
        variant.setStatus(ProductStatus.ACTIVE);
        return variants.saveAndFlush(variant).getId();
    }
}
