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
                    "ACTIVE", "ACTIVE", "ACTIVE"));
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
    void pricingUsesCatalogAndRoundsEachStoreBeforeAddingTotals() {
        doAnswer(invocation -> {
            VariantSnapshotBatchRequest request = invocation.getArgument(0);
            return request.variantIds().stream().map(id -> new VariantSnapshotResponse(id, id, id - 500,
                    id - 500, "P", "Mooncake", "Box", "SKU", null,
                    new BigDecimal(id == 501L ? "100.60" : "50.60"), 10,
                    "ACTIVE", "ACTIVE", "ACTIVE")).toList();
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
        var priced = carts.priceCart(10003L);
        assertThat(priced.stores()).hasSize(2);
        assertThat(priced.subtotal()).isEqualByComparingTo("151.20");
        assertThat(priced.totalAmount()).isEqualByComparingTo("152");
        assertThat(priced.stores().getFirst().items().getFirst().productName()).isEqualTo("Mooncake");
    }
}
