package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.commerce.client.dto.catalog.StockReservationResponse;
import mss.mooncake.commerce.client.dto.identity.AddressSummaryResponse;
import mss.mooncake.commerce.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.commerce.dto.request.CheckoutSelectionRequest;
import mss.mooncake.commerce.dto.response.CartResponse;
import mss.mooncake.commerce.dto.response.CartItemResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;
import mss.mooncake.commerce.repository.CheckoutRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RetailCheckoutServiceTest {
    /** The buyer's user account; the seller in readyCart() belongs to user 8. */
    private static final long BUYER_USER = 3L;
    private static final CheckoutSelectionRequest SELECTION = new CheckoutSelectionRequest(List.of(3L), null);
    private static final String REPLAY_QUOTE = "a".repeat(64);
    private final CheckoutRepository repository = mock(CheckoutRepository.class);
    private final CartApplicationService carts = mock(CartApplicationService.class);
    private final CatalogServiceClient catalog = mock(CatalogServiceClient.class);
    private final IdentityServiceClient identity = mock(IdentityServiceClient.class);
    private final RetailCheckoutService service = new mss.mooncake.commerce.service.impl.RetailCheckoutServiceImpl(repository, carts, catalog, identity);

    @Test
    void sameKeyReplaysSavedOrderWithoutRepricingOrReserving() {
        String key = "checkout-abc123";
        var attempt = new CheckoutRepository.Attempt(1, 7, 9, 11, key, "hash", "reservation",
                LocalDateTime.now().plusMinutes(1), "COMPLETED", 33L, "3:null:" + REPLAY_QUOTE);
        var response = new RetailCheckoutResponse(33L, "MSS-20261023-33", "AWAITING_PAYMENT",
                BigDecimal.valueOf(123), List.of());
        when(repository.byKey(7, key)).thenReturn(attempt);
        when(repository.response(33)).thenReturn(response);
        assertThat(service.place(BUYER_USER, 7, 11, key, SELECTION, REPLAY_QUOTE)).isEqualTo(response);
        verifyNoInteractions(carts, catalog, identity);
    }

    @Test
    void sameKeyWithDifferentAddressIsRejected() {
        String key = "checkout-abc123";
        when(repository.byKey(7, key)).thenReturn(new CheckoutRepository.Attempt(1, 7, 9, 11, key,
                "hash", "reservation", LocalDateTime.now().plusMinutes(1), "COMPLETED", 33L, "3:null:" + REPLAY_QUOTE));
        assertThatThrownBy(() -> service.place(BUYER_USER, 7, 12, key, SELECTION, REPLAY_QUOTE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verifyNoInteractions(carts, catalog, identity);
    }

    private CheckoutRepository.Attempt readyAttempt(String status, Long orderId, String quote) {
        return new CheckoutRepository.Attempt(1, 7, 9, 11, "checkout-abc123", "hash", "reservation",
                LocalDateTime.now().plusMinutes(15), status, orderId, "3:null:" + quote);
    }

    private void readyCart() {
        when(carts.getCart(7L)).thenReturn(new CartResponse(9L, List.of(new CartItemResponse(3L, 4L, 2, true),
                new CartItemResponse(9L, 10L, 1, false))));
        when(repository.cartFingerprint(9L)).thenReturn("hash");
        when(identity.getAddressSummary(11L)).thenReturn(new AddressSummaryResponse(11L, 7L,
                "Buyer", "0900000000", "Street", null, null, "City"));
        var line = new CartPricingResponse.LinePrice(3L, 4L, "Cake", "Box", "SKU", null, 2,
                BigDecimal.valueOf(100), BigDecimal.valueOf(200), BigDecimal.ZERO, BigDecimal.valueOf(200), true, 10);
        var store = new CartPricingResponse.StorePrice(5L, 6L, "Shop", BigDecimal.valueOf(200),
                BigDecimal.ZERO, BigDecimal.valueOf(200), List.of(), List.of(line));
        var price = new CartPricingResponse(BigDecimal.valueOf(200), BigDecimal.ZERO,
                BigDecimal.valueOf(200), List.of(store));
        when(carts.priceCart(eq(List.of(new CartItemResponse(3L, 4L, 2, true))), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(price);
        when(catalog.getStoreSummary(5L)).thenReturn(new StoreSummaryResponse(5L, 6L, "SHOP", "Shop",
                "shop", null, "ACTIVE", new BigDecimal("30.01")));
        when(identity.getSupplierSummary(6L)).thenReturn(new SupplierSummaryResponse(6L, 8L,
                "Supplier", "TAX", "VERIFIED"));
        when(repository.start(eq(7L), eq(9L), eq(11L), eq("checkout-abc123"), eq("hash"), any()))
                .thenAnswer(invocation -> readyAttempt("CREATED", null, ((String) invocation.getArgument(5)).split(":")[2]));
        when(catalog.reserveStock(any())).thenReturn(new StockReservationResponse("reservation", "HELD", null, List.of()));
    }

    @Test
    void checkoutRoundsConfiguredFeeAndConfirmsStockBeforeReturningOrder() {
        readyCart();
        var preview = service.preview(BUYER_USER, 7, SELECTION);
        assertThat(preview.grandTotal()).isEqualByComparingTo("231");
        when(repository.byKey(7L, "checkout-abc123")).thenReturn(null,
                readyAttempt("PENDING_STOCK", 33L, preview.quote()), readyAttempt("COMPLETED", 33L, preview.quote()));
        when(catalog.confirmStockReservation("reservation"))
                .thenReturn(new StockReservationResponse("reservation", "CONFIRMED", null, List.of()));
        var result = new RetailCheckoutResponse(33L, "MSS-33", "AWAITING_PAYMENT", BigDecimal.valueOf(231), List.of());
        when(repository.response(33L)).thenReturn(result);

        assertThat(service.place(BUYER_USER, 7, 11, "checkout-abc123", SELECTION, preview.quote())).isEqualTo(result);
        var stores = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).persistPending(any(), any(), stores.capture(), eq("hash"));
        assertThat(((CheckoutRepository.Store) stores.getValue().getFirst()).fee()).isEqualByComparingTo("31");
        var reservation = org.mockito.ArgumentCaptor.forClass(mss.mooncake.commerce.client.dto.catalog.StockReservationRequest.class);
        verify(catalog).reserveStock(reservation.capture());
        assertThat(reservation.getValue().lines()).containsExactly(new mss.mooncake.commerce.client.dto.catalog.StockReservationRequest.Line(4L, 2));
        verify(catalog).confirmStockReservation("reservation");
        verify(repository).complete(any());
    }

    @Test
    void losingConcurrentAttemptMustNotReleaseOtherAttemptsStock() {
        readyCart();
        var preview = service.preview(BUYER_USER, 7, SELECTION);
        when(repository.byKey(7L, "checkout-abc123")).thenReturn(null);
        doThrow(new IllegalStateException("Already finalized")).when(repository)
                .persistPending(any(), any(), any(), eq("hash"));
        when(repository.failCreated(any())).thenReturn(false);

        assertThatThrownBy(() -> service.place(BUYER_USER, 7, 11, "checkout-abc123", SELECTION, preview.quote()))
                .isInstanceOf(IllegalStateException.class);
        verify(catalog, never()).releaseStockReservation(any());
    }

    @Test
    void supplierCannotCheckOutItsOwnStoresProducts() {
        readyCart();

        // User 8 owns supplier 6, which sells every line of this cart.
        assertThatThrownBy(() -> service.place(8, 7, 11, "checkout-abc123", SELECTION, REPLAY_QUOTE))
                .isInstanceOfSatisfying(mss.mooncake.commerce.exception.CommerceException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("SELF_PURCHASE_NOT_ALLOWED");
                });
        verify(repository, never()).start(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
        verify(catalog, never()).reserveStock(any());
    }

    @Test
    void unavailableSelectedItemCannotBeCheckedOut() {
        readyCart();
        var line = new CartPricingResponse.LinePrice(3L, 4L, "Cake", "Box", "SKU", null, 2,
                BigDecimal.valueOf(100), BigDecimal.valueOf(200), BigDecimal.ZERO, BigDecimal.valueOf(200), true, 10);
        var store = new CartPricingResponse.StorePrice(5L, 6L, "Shop", BigDecimal.valueOf(200),
                BigDecimal.ZERO, BigDecimal.valueOf(200), List.of(), List.of(line));
        when(carts.priceCart(eq(List.of(new CartItemResponse(3L, 4L, 2, true))), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new CartPricingResponse(BigDecimal.valueOf(200), BigDecimal.ZERO,
                BigDecimal.valueOf(200), List.of(store), List.of(new CartPricingResponse.UnavailableLine(
                        3L, 4L, "Old cake", "Box", "SKU-OLD", null, 2, "UNAVAILABLE", null))));

        assertThatThrownBy(() -> service.place(BUYER_USER, 7, 11, "checkout-abc123", SELECTION, REPLAY_QUOTE))
                .isInstanceOfSatisfying(mss.mooncake.commerce.exception.CommerceException.class,
                        e -> assertThat(e.getCode()).isEqualTo("UNAVAILABLE"));
        verify(catalog, never()).reserveStock(any());
    }

    @Test
    void zeroPayableSellerIsRejectedBeforeReservingStock() {
        readyCart();
        when(catalog.getStoreSummary(5L)).thenReturn(new StoreSummaryResponse(5L, 6L, "SHOP", "Shop",
                "shop", null, "ACTIVE", BigDecimal.ZERO));
        var zero = BigDecimal.ZERO;
        when(carts.priceCart(eq(List.of(new CartItemResponse(3L, 4L, 2, true))), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(new CartPricingResponse(zero, zero, zero,
                List.of(new CartPricingResponse.StorePrice(5L, 6L, "Shop", zero, zero, zero,
                        List.of(), List.of()))));

        assertThatThrownBy(() -> service.place(BUYER_USER, 7, 11, "checkout-abc123", SELECTION, REPLAY_QUOTE))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(catalog, never()).reserveStock(any());
    }
}
