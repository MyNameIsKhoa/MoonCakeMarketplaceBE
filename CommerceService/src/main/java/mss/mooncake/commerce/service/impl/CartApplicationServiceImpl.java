package mss.mooncake.commerce.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.dto.catalog.PromotionCalculationRequest;
import mss.mooncake.commerce.client.dto.catalog.PromotionCalculationResult;
import mss.mooncake.commerce.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotResponse;
import mss.mooncake.commerce.dto.request.AddCartItemRequest;
import mss.mooncake.commerce.dto.response.CartItemResponse;
import mss.mooncake.commerce.dto.response.CartResponse;
import mss.mooncake.commerce.dto.response.CartPricingResponse;
import mss.mooncake.commerce.entity.CartEntity;
import mss.mooncake.commerce.entity.CartItemEntity;
import mss.mooncake.commerce.exception.CommerceException;
import mss.mooncake.commerce.repository.CartItemRepository;
import mss.mooncake.commerce.repository.CartRepository;
import mss.mooncake.commerce.service.CartApplicationService;
import mss.mooncake.commerce.dto.internal.CartItemChange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;

@Service
public class CartApplicationServiceImpl implements CartApplicationService {
    private static final String ACTIVE = "ACTIVE";
    private final CartRepository carts;
    private final CartItemRepository items;
    private final CatalogServiceClient catalog;

    public CartApplicationServiceImpl(CartRepository carts, CartItemRepository items,
                                      CatalogServiceClient catalog) {
        this.carts = carts;
        this.items = items;
        this.catalog = catalog;
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(Long customerId) {
        return carts.findFirstByCustomerIdRefAndStatusOrderByIdDesc(customerId, ACTIVE)
                .map(cart -> new CartResponse(cart.getId(), items.findByCartIdOrderByIdAsc(cart.getId())
                        .stream().map(this::toResponse).toList()))
                .orElseGet(() -> new CartResponse(null, List.of()));
    }

    @Override
    public CartPricingResponse priceCart(List<CartItemResponse> cartItems, boolean checkAvailability) {
        if (cartItems.isEmpty()) {
            return new CartPricingResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        List<VariantSnapshotResponse> snapshots = new ArrayList<>();
        for (int start = 0; start < cartItems.size(); start += 100) {
            var batch = cartItems.subList(start, Math.min(start + 100, cartItems.size()));
            var received = catalog.getVariantSnapshots(new VariantSnapshotBatchRequest(
                    batch.stream().map(CartItemResponse::variantId).toList()));
            if (received == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không lấy được thông tin sản phẩm, vui lòng thử lại sau");
            snapshots.addAll(received);
        }
        Map<Long, VariantSnapshotResponse> byVariant = snapshots.stream()
                .collect(Collectors.toMap(VariantSnapshotResponse::variantId, Function.identity()));
        if (byVariant.size() != cartItems.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Thông tin sản phẩm trong giỏ chưa đầy đủ, vui lòng thử lại sau");
        }
        Map<Long, List<CartItemResponse>> byStore = new LinkedHashMap<>();
        List<CartPricingResponse.UnavailableLine> unavailable = new ArrayList<>();
        for (var item : cartItems) {
            var snapshot = byVariant.get(item.variantId());
            if (snapshot == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Thông tin sản phẩm trong giỏ chưa đầy đủ, vui lòng thử lại sau");
            }
            // A line that is no longer for sale is reported, not priced, so the rest of the cart stays usable.
            if (snapshot.storeId() == null || snapshot.supplierId() == null
                    || !"ACTIVE".equals(snapshot.variantStatus())
                    || !"ACTIVE".equals(snapshot.productStatus())
                    || !"ACTIVE".equals(snapshot.storeStatus())) {
                unavailable.add(new CartPricingResponse.UnavailableLine(item.cartItemId(), item.variantId(),
                        snapshot.productName(), snapshot.variantName(), snapshot.sku(), snapshot.imageUrl(),
                        item.quantity(), "UNAVAILABLE", null));
                continue;
            }
            if (checkAvailability && snapshot.availableQuantity() != null && item.quantity() > snapshot.availableQuantity()) {
                unavailable.add(new CartPricingResponse.UnavailableLine(item.cartItemId(), item.variantId(),
                        snapshot.productName(), snapshot.variantName(), snapshot.sku(), snapshot.imageUrl(),
                        item.quantity(), "INSUFFICIENT_STOCK", snapshot.availableQuantity()));
                continue;
            }
            byStore.computeIfAbsent(snapshot.storeId(), ignored -> new ArrayList<>()).add(item);
        }
        List<CartPricingResponse.StorePrice> storePrices = new ArrayList<>();
        for (var entry : byStore.entrySet()) {
            Long storeId = entry.getKey();
            List<CartItemResponse> storeItems = entry.getValue();
            if (storeItems.size() > 100) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A store has more variants than the pricing contract supports");
            }
            StoreSummaryResponse store = catalog.getStoreSummary(storeId);
            if (store == null || !storeId.equals(store.storeId()) || store.supplierIdRef() == null
                    || !"ACTIVE".equals(store.status())
                    || storeItems.stream().anyMatch(item -> !store.supplierIdRef()
                            .equals(byVariant.get(item.variantId()).supplierId()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cửa hàng hiện đã ngừng bán");
            }
            PromotionCalculationResult priced = catalog.calculatePromotions(
                    new PromotionCalculationRequest(storeId, storeItems.stream()
                            .map(item -> new PromotionCalculationRequest.Line(item.variantId(), item.quantity()))
                            .toList()));
            if (priced == null || !storeId.equals(priced.storeId()) || priced.lines() == null
                    || priced.lines().size() != storeItems.size() || priced.appliedPromotions() == null
                    || priced.subtotal() == null || priced.discountTotal() == null || priced.totalAmount() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Không lấy được đầy đủ giá sản phẩm, vui lòng thử lại sau");
            }
            Map<Long, PromotionCalculationResult.LineResult> pricedByVariant = priced.lines().stream()
                    .collect(Collectors.toMap(PromotionCalculationResult.LineResult::variantId, Function.identity()));
            List<CartPricingResponse.LinePrice> display = storeItems.stream().map(item -> {
                var snapshot = byVariant.get(item.variantId());
                var line = pricedByVariant.get(item.variantId());
                if (line == null || line.quantity() == null || item.quantity() != line.quantity()) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dữ liệu giỏ hàng vừa thay đổi, vui lòng tải lại và thử lại");
                }
                return new CartPricingResponse.LinePrice(item.cartItemId(), item.variantId(),
                        snapshot.productName(), snapshot.variantName(), snapshot.sku(), snapshot.imageUrl(),
                        item.quantity(), line.unitPrice(), line.lineSubtotal(), line.discountAmount(),
                        line.lineTotal(), item.selected(), snapshot.availableQuantity());
            }).toList();
            storePrices.add(new CartPricingResponse.StorePrice(storeId, store.supplierIdRef(), store.name(),
                    priced.subtotal(), priced.discountTotal(), priced.totalAmount(),
                    priced.appliedPromotions().stream().map(PromotionCalculationResult.AppliedPromotion::promotionName)
                            .distinct().toList(), display));
        }
        return new CartPricingResponse(
                storePrices.stream().map(CartPricingResponse.StorePrice::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                storePrices.stream().map(CartPricingResponse.StorePrice::discountTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                storePrices.stream().map(CartPricingResponse.StorePrice::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                storePrices, List.copyOf(unavailable));
    }

    /** Catalog's snapshot quantity is the stock still free to sell (on hand minus active holds). */
    private static void requireStock(VariantSnapshotResponse snapshot, int quantity) {
        if (snapshot.availableQuantity() != null && quantity > snapshot.availableQuantity()) {
            throw new CommerceException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                    "Chỉ còn " + Math.max(0, snapshot.availableQuantity()) + " sản phẩm trong kho");
        }
    }

    private VariantSnapshotResponse snapshot(Long variantId) {
        List<VariantSnapshotResponse> snapshots = catalog.getVariantSnapshots(
                new VariantSnapshotBatchRequest(List.of(variantId)));
        if (snapshots == null || snapshots.size() != 1 || !variantId.equals(snapshots.getFirst().variantId())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Thông tin sản phẩm không hợp lệ, vui lòng thử lại sau");
        }
        return snapshots.getFirst();
    }

    @Override
    @Transactional
    public CartItemChange addItem(Long customerId, AddCartItemRequest request) {
        VariantSnapshotResponse snapshot = snapshot(request.variantId());
        if (!"ACTIVE".equals(snapshot.variantStatus()) || !"ACTIVE".equals(snapshot.productStatus())
                || !"ACTIVE".equals(snapshot.storeStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sản phẩm hiện đã ngừng bán");
        }
        CartEntity current = carts.findFirstByCustomerIdRefAndStatusOrderByIdDesc(customerId, ACTIVE)
                .orElseGet(() -> createCart(customerId));
        CartEntity cart = carts.lockActive(current.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Giỏ hàng đang được thanh toán, vui lòng chờ hoàn tất"));
        var existing = items.findByCartIdAndVariantIdRef(cart.getId(), request.variantId());
        CartItemEntity item = existing
                .orElseGet(() -> {
                    CartItemEntity created = new CartItemEntity();
                    created.setCartId(cart.getId());
                    created.setVariantIdRef(request.variantId());
                    created.setQuantity(0);
                    created.setAddedAt(LocalDateTime.now(ZoneOffset.UTC));
                    return created;
                });
        int quantity;
        try {
            quantity = Math.addExact(item.getQuantity(), request.quantity());
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số lượng vượt quá giới hạn cho phép");
        }
        requireStock(snapshot, quantity);
        item.setQuantity(quantity);
        cart.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return new CartItemChange(toResponse(items.save(item)), existing.isEmpty());
    }

    @Override
    @Transactional
    public CartItemResponse updateItem(Long customerId, Long itemId, int quantity) {
        if (quantity <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số lượng phải lớn hơn 0");
        CartEntity cart = requireCart(customerId);
        CartItemEntity item = items.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm trong giỏ hàng"));
        requireStock(snapshot(item.getVariantIdRef()), quantity);
        item.setQuantity(quantity);
        cart.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return toResponse(items.save(item));
    }

    @Override
    @Transactional
    public void select(Long customerId, List<Long> itemIds) {
        var requested = new java.util.HashSet<>(itemIds);
        if (requested.size() != itemIds.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate cart items");
        var cart = requireCart(customerId);
        var lines = items.findByCartIdOrderByIdAsc(cart.getId());
        if (lines.stream().filter(line -> requested.contains(line.getId())).count() != requested.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Giỏ hàng đã thay đổi. Vui lòng tải lại.");
        }
        lines.forEach(line -> line.setSelected(requested.contains(line.getId())));
    }

    @Override
    @Transactional
    public void removeItem(Long customerId, Long itemId) {
        CartEntity cart = requireCart(customerId);
        CartItemEntity item = items.findByIdAndCartId(itemId, cart.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm trong giỏ hàng"));
        items.delete(item);
        cart.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public void clear(Long customerId) {
        carts.findFirstByCustomerIdRefAndStatusOrderByIdDesc(customerId, ACTIVE).ifPresent(cart -> {
            carts.lockActive(cart.getId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Giỏ hàng đang được thanh toán, vui lòng chờ hoàn tất"));
            items.deleteByCartId(cart.getId());
            cart.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        });
    }

    private CartEntity createCart(Long customerId) {
        if (carts.existsByCustomerIdRefAndStatus(customerId, "CHECKOUT_PENDING"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Giỏ hàng đang được thanh toán, vui lòng chờ hoàn tất");
        CartEntity cart = new CartEntity();
        cart.setCustomerIdRef(customerId);
        cart.setStatus(ACTIVE);
        cart.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        cart.setUpdatedAt(cart.getCreatedAt());
        try { return carts.saveAndFlush(cart); }
        catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Đã có giỏ hàng hoặc phiên thanh toán đang hoạt động", conflict);
        }
    }

    private CartEntity requireCart(Long customerId) {
        return carts.findFirstByCustomerIdRefAndStatusOrderByIdDesc(customerId, ACTIVE)
                .flatMap(cart -> carts.lockActive(cart.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giỏ hàng"));
    }

    private CartItemResponse toResponse(CartItemEntity item) {
        return new CartItemResponse(item.getId(), item.getVariantIdRef(), item.getQuantity(), item.isSelected());
    }
}
