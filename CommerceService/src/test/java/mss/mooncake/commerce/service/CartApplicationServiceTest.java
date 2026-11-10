package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.List;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotResponse;
import mss.mooncake.commerce.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.commerce.client.dto.catalog.PromotionCalculationRequest;
import mss.mooncake.commerce.client.dto.catalog.PromotionCalculationResult;
import mss.mooncake.commerce.dto.request.AddCartItemRequest;
import mss.mooncake.commerce.dto.response.CartPricingResponse;
import mss.mooncake.commerce.exception.CommerceException;
import org.springframework.http.HttpStatus;
import mss.mooncake.commerce.repository.CartItemRepository;
import mss.mooncake.commerce.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CartApplicationServiceTest {
    @Autowired private CartApplicationService carts;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository itemRepository;
    @MockitoBean private CatalogServiceClient catalog;

    @BeforeEach
    void catalogHasActiveVariant() {
        when(catalog.getVariantSnapshots(any())).thenAnswer(invocation -> {
            VariantSnapshotBatchRequest request = invocation.getArgument(0);
            return List.of(new VariantSnapshotResponse(request.variantIds().getFirst(), 1L, 1L, 1L,
                    "P", "Product", "Variant", "SKU", null, new BigDecimal("100"), 10,
                    "ACTIVE", "ACTIVE", "ACTIVE", 10));
        });
    }

    @Test
    void readingEmptyCartDoesNotCreateOne() {
        long before = cartRepository.count();
        assertThat(carts.getCart(10001L).items()).isEmpty();
        assertThat(cartRepository.count()).isEqualTo(before);
    }

    @Test
    void addingSameVariantAccumulatesAndOnlyOwnerCanChangeIt() {
        long customerId = 10002L;
        var first = carts.addItem(customerId, new AddCartItemRequest(501L, 2));
        var second = carts.addItem(customerId, new AddCartItemRequest(501L, 3));
        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.item().cartItemId()).isEqualTo(first.item().cartItemId());
        assertThat(second.item().quantity()).isEqualTo(5);
        assertThat(itemRepository.count()).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> carts.updateItem(99999L, first.item().cartItemId(), 1))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(carts.updateItem(customerId, first.item().cartItemId(), 4).quantity()).isEqualTo(4);
        carts.clear(customerId);
        assertThat(carts.getCart(customerId).items()).isEmpty();
    }

    @Test
    void addingMoreThanTheAvailableStockIsRejected() {
        long customerId = 10004L;
        carts.addItem(customerId, new AddCartItemRequest(601L, 6));

        assertThatThrownBy(() -> carts.addItem(customerId, new AddCartItemRequest(601L, 5)))
                .isInstanceOfSatisfying(CommerceException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                });
        assertThatThrownBy(() -> carts.addItem(10005L, new AddCartItemRequest(602L, 11)))
                .isInstanceOf(CommerceException.class);
        assertThat(carts.getCart(customerId).items().getFirst().quantity()).isEqualTo(6);
    }

    @Test
    void updatingAboveTheAvailableStockIsRejected() {
        long customerId = 10006L;
        var item = carts.addItem(customerId, new AddCartItemRequest(603L, 2)).item();

        assertThatThrownBy(() -> carts.updateItem(customerId, item.cartItemId(), 11))
                .isInstanceOfSatisfying(CommerceException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(carts.updateItem(customerId, item.cartItemId(), 10).quantity()).isEqualTo(10);
    }

    @Test
    void inactiveLineIsFlaggedUnavailableInsteadOfFailingTheWholeCart() {
        long customerId = 10007L;
        carts.addItem(customerId, new AddCartItemRequest(701L, 1));
        var stale = carts.addItem(customerId, new AddCartItemRequest(702L, 2)).item();
        doAnswer(invocation -> {
            VariantSnapshotBatchRequest request = invocation.getArgument(0);
            return request.variantIds().stream().map(id -> new VariantSnapshotResponse(id, id, 1L, 1L, "P",
                    "Mooncake " + id, "Box", "SKU" + id, null, new BigDecimal("100"), 10,
                    id == 702L ? "INACTIVE" : "ACTIVE", "ACTIVE", "ACTIVE", 10)).toList();
        }).when(catalog).getVariantSnapshots(any());
        when(catalog.getStoreSummary(1L))
                .thenReturn(new StoreSummaryResponse(1L, 1L, "S1", "Store 1", "s1", null, "ACTIVE"));
        when(catalog.calculatePromotions(any())).thenAnswer(invocation -> {
            PromotionCalculationRequest request = invocation.getArgument(0);
            assertThat(request.lines()).extracting(PromotionCalculationRequest.Line::variantId).containsExactly(701L);
            BigDecimal price = new BigDecimal("100");
            return new PromotionCalculationResult(1L, java.time.LocalDateTime.now(), price, BigDecimal.ZERO, price,
                    List.of(new PromotionCalculationResult.LineResult(701L, 1, price, price, BigDecimal.ZERO,
                            price, List.of())), List.of());
        });

        var priced = carts.priceCart(carts.getCart(customerId).items(), true);

        assertThat(priced.totalAmount()).isEqualByComparingTo("100");
        assertThat(priced.stores()).singleElement().satisfies(store -> assertThat(store.items())
                .extracting(CartPricingResponse.LinePrice::variantId).containsExactly(701L));
        assertThat(priced.unavailableItems()).singleElement().satisfies(line -> {
            assertThat(line.cartItemId()).isEqualTo(stale.cartItemId());
            assertThat(line.variantId()).isEqualTo(702L);
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.productName()).isEqualTo("Mooncake 702");
            assertThat(line.reason()).isEqualTo("UNAVAILABLE");
        });
    }

    @Test
    void pricingUsesCatalogAndRoundsEachStoreBeforeAddingTotals() {
        doAnswer(invocation -> {
            VariantSnapshotBatchRequest request = invocation.getArgument(0);
            return request.variantIds().stream().map(id -> new VariantSnapshotResponse(id, id, id - 500,
                    id - 500, "P", "Mooncake", "Box", "SKU", null,
                    new BigDecimal(id == 501L ? "100.60" : "50.60"), 10,
                    "ACTIVE", "ACTIVE", "ACTIVE", 10)).toList();
        }).when(catalog).getVariantSnapshots(any());
        when(catalog.getStoreSummary(1L))
                .thenReturn(new StoreSummaryResponse(1L, 1L, "S1", "Store 1", "s1", null, "ACTIVE"));
        when(catalog.getStoreSummary(2L))
                .thenReturn(new StoreSummaryResponse(2L, 2L, "S2", "Store 2", "s2", null, "ACTIVE"));
        when(catalog.calculatePromotions(any())).thenAnswer(invocation -> {
            PromotionCalculationRequest request = invocation.getArgument(0);
            boolean first = request.storeId() == 1L;
            BigDecimal price = new BigDecimal(first ? "100.60" : "50.60");
            return new PromotionCalculationResult(request.storeId(), java.time.LocalDateTime.now(), price,
                    BigDecimal.ZERO, new BigDecimal(first ? "101" : "51"),
                    List.of(new PromotionCalculationResult.LineResult(request.lines().getFirst().variantId(),
                            1, price, price, BigDecimal.ZERO, price, List.of())), List.of());
        });
        carts.addItem(10003L, new AddCartItemRequest(501L, 1));
        carts.addItem(10003L, new AddCartItemRequest(502L, 1));
        var priced = carts.priceCart(carts.getCart(10003L).items(), true);
        assertThat(priced.stores()).hasSize(2);
        assertThat(priced.subtotal()).isEqualByComparingTo("151.20");
        assertThat(priced.totalAmount()).isEqualByComparingTo("152");
        assertThat(priced.stores().getFirst().items().getFirst().productName()).isEqualTo("Mooncake");
    }
}
