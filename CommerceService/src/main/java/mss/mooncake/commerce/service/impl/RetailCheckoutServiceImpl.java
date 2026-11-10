package mss.mooncake.commerce.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.catalog.StockReservationRequest;
import mss.mooncake.commerce.client.dto.catalog.StockReservationResponse;
import mss.mooncake.commerce.dto.request.CheckoutSelectionRequest;
import mss.mooncake.commerce.dto.response.CartItemResponse;
import mss.mooncake.commerce.dto.response.RetailCheckoutPreview;
import mss.mooncake.commerce.dto.response.RetailCheckoutResponse;
import mss.mooncake.commerce.exception.CommerceException;
import mss.mooncake.commerce.repository.CheckoutRepository;
import mss.mooncake.commerce.service.RetailCheckoutService;
import mss.mooncake.commerce.service.CartApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RetailCheckoutServiceImpl implements RetailCheckoutService {
    private final CheckoutRepository checkout;
    private final CartApplicationService carts;
    private final CatalogServiceClient catalog;
    private final IdentityServiceClient identity;

    public RetailCheckoutServiceImpl(CheckoutRepository checkout, CartApplicationService carts,
                                 CatalogServiceClient catalog, IdentityServiceClient identity) {
        this.checkout = checkout;
        this.carts = carts;
        this.catalog = catalog;
        this.identity = identity;
    }

    @Override
    public RetailCheckoutPreview preview(long userId, long customerId, CheckoutSelectionRequest selection) {
        var stores = price(userId, selectedLines(carts.getCart(customerId).items(), selection), true);
        var groups = stores.stream().map(s -> new RetailCheckoutPreview.Group(s.price(), s.fee())).toList();
        BigDecimal subtotal = stores.stream().map(s -> s.price().subtotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = stores.stream().map(s -> s.price().discountTotal()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shipping = stores.stream().map(CheckoutRepository.Store::fee).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = stores.stream().map(s -> s.price().totalAmount().add(s.fee()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RetailCheckoutPreview(quote(stores), groups, subtotal, discount, shipping, payable);
    }

    @Override
    public RetailCheckoutResponse place(long userId, long customerId, long addressId, String key,
                                        CheckoutSelectionRequest selection, String quote) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{8,100}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 8-100 safe characters");
        if (addressId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid addressId");
        String selectionRequest = selection.cartItemIds().stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(",")) + ":" + selection.quantity() + ":" + quote;
        var previous = checkout.byKey(customerId, key);
        if (previous != null) {
            if (previous.addressId() != addressId)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key used with another address");
            if (!Objects.equals(previous.selectionRequest(), selectionRequest))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key used with another checkout request");
            if ("COMPLETED".equals(previous.status())) return checkout.response(previous.orderId());
            if ("PENDING_STOCK".equals(previous.status())) return reconcile(previous);
            if ("FAILED".equals(previous.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout attempt failed; use a new key");
        }
        var cart = carts.getCart(customerId);
        if (cart.cartId() == null || cart.items().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart is empty");
        String fingerprint = checkout.cartFingerprint(cart.cartId());
        if (!cart.equals(carts.getCart(customerId))) throw changedCart();
        var lines = selectedLines(cart.items(), selection);
        if (previous != null && (previous.cartId() != cart.cartId()
                || !previous.fingerprint().equals(fingerprint)))
            throw changedCart();
        var address = identity.getAddressSummary(addressId);
        if (address == null || !Long.valueOf(customerId).equals(address.customerId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Address does not belong to customer");
        List<CheckoutRepository.Store> stores;
        try {
            // A retry may already own a hold for these lines. Reserving with its original key verifies the hold.
            stores = price(userId, lines, previous == null);
            requireQuote(stores, quote);
        } catch (CommerceException conflict) {
            if (previous != null && conflict.getStatus() == HttpStatus.CONFLICT) failAndRelease(previous);
            throw conflict;
        }
        CheckoutRepository.Attempt attempt;
        try {
            attempt = previous == null
                    ? checkout.start(customerId, cart.cartId(), addressId, key, fingerprint, selectionRequest) : previous;
        } catch (DataIntegrityViolationException concurrent) {
            var existing = checkout.byKey(customerId, key);
            if (existing != null) {
                if (existing.addressId() != addressId || !Objects.equals(existing.selectionRequest(), selectionRequest)) {
                    throw new CommerceException(HttpStatus.CONFLICT, "CHECKOUT_KEY_MISMATCH",
                            "Yêu cầu đặt hàng này đã được dùng cho nội dung khác.");
                }
                if ("COMPLETED".equals(existing.status())) return checkout.response(existing.orderId());
                if ("PENDING_STOCK".equals(existing.status())) return reconcile(existing);
                if ("CREATED".equals(existing.status())) throw new CommerceException(HttpStatus.SERVICE_UNAVAILABLE,
                        "CHECKOUT_IN_PROGRESS", "Đơn đang được xử lý. Vui lòng kiểm tra lại lần đặt này.");
            }
            throw new CommerceException(HttpStatus.CONFLICT, "CHECKOUT_CART_BUSY",
                    "Giỏ hàng đang được xử lý ở một lần đặt khác. Vui lòng kiểm tra Đơn hàng.");
        }
        if (!attempt.fingerprint().equals(fingerprint))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart changed during checkout");
        if (!attempt.expiresAt().isAfter(java.time.LocalDateTime.now(ZoneOffset.UTC))) {
            checkout.failCreated(attempt);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout attempt expired; use a new key");
        }
        try {
            var reservationLines = lines.stream()
                    .map(item -> new StockReservationRequest.Line(item.variantId(), item.quantity())).toList();
            StockReservationResponse held = catalog.reserveStock(new StockReservationRequest(
                    attempt.reservationKey(), attempt.expiresAt().atOffset(ZoneOffset.UTC), reservationLines));
            if (held == null || !"HELD".equals(held.status()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock reservation was not held");
            requireQuote(price(userId, lines, false), quote);
            checkout.persistPending(attempt, address, stores, fingerprint);
        } catch (RuntimeException failure) {
            failAndRelease(attempt);
            throw failure;
        }
        return reconcile(checkout.byKey(customerId, key));
    }

    private void failAndRelease(CheckoutRepository.Attempt attempt) {
        if (checkout.failCreated(attempt)) {
            try { catalog.releaseStockReservation(attempt.reservationKey()); }
            catch (RuntimeException ignored) { /* The hold expires automatically. */ }
        }
    }

    private List<CartItemResponse> selectedLines(List<CartItemResponse> cart, CheckoutSelectionRequest selection) {
        var ids = new java.util.HashSet<>(selection.cartItemIds());
        if (ids.isEmpty() || ids.size() != selection.cartItemIds().size()
                || (selection.quantity() != null && ids.size() != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách sản phẩm đặt hàng không hợp lệ");
        }
        var selected = cart.stream().filter(line -> ids.contains(line.cartItemId())).toList();
        if (selected.size() != ids.size()) throw changedCart();
        if (selection.quantity() == null) return selected;
        var line = selected.getFirst();
        if (selection.quantity() > line.quantity()) throw changedCart();
        return List.of(new CartItemResponse(line.cartItemId(), line.variantId(), selection.quantity(), line.selected()));
    }

    private CommerceException changedCart() {
        return new CommerceException(HttpStatus.CONFLICT, "CHECKOUT_CART_CHANGED",
                "Giỏ hàng đã thay đổi. Vui lòng kiểm tra lại sản phẩm trước khi đặt.");
    }

    private List<CheckoutRepository.Store> price(long userId, List<CartItemResponse> lines, boolean checkAvailability) {
        var pricing = carts.priceCart(lines, checkAvailability);
        if (!pricing.unavailableItems().isEmpty()) {
            var line = pricing.unavailableItems().getFirst();
            String reason = "INSUFFICIENT_STOCK".equals(line.reason())
                    ? " chỉ còn " + line.availableQuantity() + " sản phẩm." : " hiện không còn bán.";
            throw new CommerceException(HttpStatus.CONFLICT, line.reason(), line.productName() + reason);
        }
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
            if (supplier.userId() != null && supplier.userId() == userId)
                throw new CommerceException(HttpStatus.CONFLICT, "SELF_PURCHASE_NOT_ALLOWED",
                        "Không thể mua sản phẩm của chính cửa hàng mình: " + price.storeName());
            stores.add(new CheckoutRepository.Store(price,
                    store.shippingFee().setScale(0, RoundingMode.CEILING), supplier.companyName()));
        }
        if (stores.stream().anyMatch(s -> s.price().totalAmount().add(s.fee()).signum() <= 0))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Every seller order must have a positive payable amount");
        return stores;
    }

    private void requireQuote(List<CheckoutRepository.Store> stores, String expected) {
        if (!quote(stores).equals(expected)) {
            throw new CommerceException(HttpStatus.CONFLICT, "CHECKOUT_QUOTE_CHANGED",
                    "Giá, ưu đãi hoặc phí vận chuyển đã thay đổi. Vui lòng kiểm tra lại.");
        }
    }

    private String quote(List<CheckoutRepository.Store> stores) {
        var contents = new StringBuilder();
        for (var store : stores) {
            contents.append(store.price().storeId()).append(":").append(store.fee()).append(":")
                    .append(store.price().totalAmount()).append(";");
            for (var line : store.price().items()) {
                contents.append(line.cartItemId()).append(":").append(line.variantId()).append(":")
                        .append(line.quantity()).append(":").append(line.unitPrice()).append(":")
                        .append(line.discountAmount()).append(";");
            }
            contents.append("|");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contents.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
