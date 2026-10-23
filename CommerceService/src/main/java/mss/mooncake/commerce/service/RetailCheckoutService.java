package mss.mooncake.commerce.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.catalog.StockReservationRequest;
import mss.mooncake.commerce.client.dto.catalog.StockReservationResponse;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;
import mss.mooncake.commerce.repository.CheckoutRepository;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RetailCheckoutService {
    private final CheckoutRepository checkout;
    private final CartApplicationService carts;
    private final CatalogServiceClient catalog;
    private final IdentityServiceClient identity;

    public RetailCheckoutService(CheckoutRepository checkout, CartApplicationService carts,
                                 CatalogServiceClient catalog, IdentityServiceClient identity) {
        this.checkout = checkout;
        this.carts = carts;
        this.catalog = catalog;
        this.identity = identity;
    }

    public RetailCheckoutResponse place(long customerId, long addressId, String key) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{8,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 8-100 safe characters");
        if (addressId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid addressId");
        var previous = checkout.byKey(customerId, key);
        if (previous != null) {
            if (previous.addressId() != addressId)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key used with another address");
            if ("COMPLETED".equals(previous.status())) return checkout.response(previous.orderId());
            if ("PENDING_STOCK".equals(previous.status())) return reconcile(previous);
            if ("FAILED".equals(previous.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout attempt failed; use a new key");
        }
        var cart = carts.getCart(customerId);
        if (cart.cartId() == null || cart.items().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart is empty");
        String fingerprint = checkout.cartFingerprint(cart.cartId());
        if (previous != null && (previous.cartId() != cart.cartId()
                || !previous.fingerprint().equals(fingerprint)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart changed during checkout retry");
        var address = identity.getAddressSummary(addressId);
        if (address == null || !Long.valueOf(customerId).equals(address.customerId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Address does not belong to customer");
        var pricing = carts.priceCart(customerId);
        if (pricing.stores() == null || pricing.stores().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart cannot be priced");
        List<CheckoutRepository.Store> stores = new ArrayList<>();
        for (var price : pricing.stores()) {
            var store = catalog.getStoreSummary(price.storeId());
            if (store == null || !price.storeId().equals(store.storeId())
                    || !price.supplierId().equals(store.supplierIdRef()) || !"ACTIVE".equals(store.status())
                    || store.shippingFee() == null || store.shippingFee().compareTo(BigDecimal.ZERO) < 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seller shipping fee is not configured");
            var supplier = identity.getSupplierSummary(price.supplierId());
            if (supplier == null || !price.supplierId().equals(supplier.supplierId())
                    || !"VERIFIED".equals(supplier.verificationStatus()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seller is unavailable");
            stores.add(new CheckoutRepository.Store(price,
                    store.shippingFee().setScale(0, RoundingMode.CEILING), supplier.companyName()));
        }
        if (stores.stream().anyMatch(s -> s.price().totalAmount().add(s.fee()).signum() <= 0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Every seller order must have a positive payable amount");
        CheckoutRepository.Attempt attempt;
        try {
            attempt = previous == null
                    ? checkout.start(customerId, cart.cartId(), addressId, key, fingerprint) : previous;
        } catch (DataIntegrityViolationException concurrent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cart checkout already started; retry the same Idempotency-Key", concurrent);
        }
        if (!attempt.fingerprint().equals(fingerprint))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart changed during checkout");
        if (!attempt.expiresAt().isAfter(java.time.LocalDateTime.now(ZoneOffset.UTC))) {
            checkout.failCreated(attempt);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout attempt expired; use a new key");
        }
        try {
            var lines = cart.items().stream()
                    .map(item -> new StockReservationRequest.Line(item.variantId(), item.quantity())).toList();
            StockReservationResponse held = catalog.reserveStock(new StockReservationRequest(
                    attempt.reservationKey(), attempt.expiresAt().atOffset(ZoneOffset.UTC), lines));
            if (held == null || !"HELD".equals(held.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock reservation was not held");
            if (!pricing.equals(carts.priceCart(customerId)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart price changed; review totals and retry");
            checkout.persistPending(attempt, address, stores, fingerprint);
        } catch (RuntimeException failure) {
            if (checkout.failCreated(attempt)) {
                try { catalog.releaseStockReservation(attempt.reservationKey()); }
                catch (RuntimeException ignored) { /* TTL releases any orphaned hold. */ }
            }
            throw failure;
        }
        return reconcile(checkout.byKey(customerId, key));
    }

    private RetailCheckoutResponse reconcile(CheckoutRepository.Attempt attempt) {
        StockReservationResponse stock;
        try {
            stock = catalog.confirmStockReservation(attempt.reservationKey());
        } catch (RuntimeException uncertain) {
            try { stock = catalog.getStockReservation(attempt.reservationKey()); }
            catch (RuntimeException unavailable) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Stock confirmation pending; retry with the same Idempotency-Key", uncertain);
            }
        }
        if (stock != null && "CONFIRMED".equals(stock.status())) {
            checkout.complete(attempt);
            var current = checkout.byKey(attempt.customerId(), attempt.key());
            if (current == null || !"COMPLETED".equals(current.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout was not finalized");
            return checkout.response(current.orderId());
        }
        if (stock != null && ("EXPIRED".equals(stock.status()) || "RELEASED".equals(stock.status()))) {
            checkout.expire(attempt);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock hold expired; use a new checkout key");
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Stock confirmation pending; retry with the same Idempotency-Key");
    }

    @Scheduled(cron = "${commerce.checkout.recovery-cron:*/30 * * * * *}")
    public void recoverPending() {
        for (var attempt : checkout.pending()) {
            try { reconcile(attempt); }
            catch (RuntimeException ignored) { /* Persisted attempt is retried on the next sweep or client retry. */ }
        }
        checkout.purgeExpiredKeys();
    }
}
