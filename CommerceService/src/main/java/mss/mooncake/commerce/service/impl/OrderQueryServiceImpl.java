package mss.mooncake.commerce.service.impl;

import feign.FeignException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotResponse;
import mss.mooncake.commerce.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.commerce.dto.response.DeliveryAddressResponse;
import mss.mooncake.commerce.dto.response.OrderDetailResponse;
import mss.mooncake.commerce.dto.response.OrderItemResponse;
import mss.mooncake.commerce.dto.response.OrderSummaryResponse;
import mss.mooncake.commerce.dto.response.SellerOrderResponse;
import mss.mooncake.commerce.dto.response.SellerOrderSummaryResponse;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import mss.mooncake.commerce.entity.DeliveryAddressEntity;
import mss.mooncake.commerce.entity.OrderItemEntity;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import mss.mooncake.commerce.repository.CustomerOrderRepository;
import mss.mooncake.commerce.repository.CancellationRepository;
import mss.mooncake.commerce.repository.DeliveryAddressRepository;
import mss.mooncake.commerce.repository.OrderItemRepository;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import mss.mooncake.commerce.service.OrderQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderQueryServiceImpl implements OrderQueryService {
    private final CustomerOrderRepository orders;
    private final DeliveryAddressRepository addresses;
    private final SellerOrderRepository sellerOrders;
    private final OrderItemRepository items;
    private final IdentityServiceClient identity;
    private final CancellationRepository cancellations;
    private final CatalogServiceClient catalog;

    public OrderQueryServiceImpl(CustomerOrderRepository orders, DeliveryAddressRepository addresses,
                                 SellerOrderRepository sellerOrders, OrderItemRepository items,
                                 IdentityServiceClient identity, CancellationRepository cancellations,
                                 CatalogServiceClient catalog) {
        this.orders = orders;
        this.addresses = addresses;
        this.sellerOrders = sellerOrders;
        this.items = items;
        this.identity = identity;
        this.cancellations = cancellations;
        this.catalog = catalog;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listCustomerOrders(Long customerId, Pageable pageable) {
        if (pageable.getPageSize() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số size không được vượt quá 100");
        Page<CustomerOrderEntity> page = orders.findByCustomerIdRef(customerId, pageable);
        List<SellerOrderEntity> sellers = page.isEmpty() ? List.of()
                : sellerOrders.findByOrderIdInOrderByIdAsc(page.map(CustomerOrderEntity::getId).getContent());
        Map<Long, List<SellerOrderEntity>> sellersByOrder = sellers.stream()
                .collect(Collectors.groupingBy(SellerOrderEntity::getOrderId));
        List<Long> sellerIds = sellers.stream().map(SellerOrderEntity::getId).toList();
        Map<Long, Long> itemCounts = sellers.isEmpty() ? Map.of()
                : items.findBySellerOrderIdIn(sellerIds).stream()
                        .collect(Collectors.groupingBy(OrderItemEntity::getSellerOrderId, Collectors.counting()));
        Set<Long> cancelled = sellers.isEmpty() ? Set.of()
                : Set.copyOf(cancellations.sellerOrderIdsWithCancellation(sellerIds));
        return page.map(order -> summary(order, sellersByOrder.getOrDefault(order.getId(), List.of()),
                itemCounts, cancelled));
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailResponse getCustomerOrder(Long customerId, Long orderId) {
        CustomerOrderEntity order = orders.findByIdAndCustomerIdRef(orderId, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng"));
        DeliveryAddressEntity address = addresses.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Order address snapshot missing"));
        List<SellerOrderEntity> sellers = sellerOrders.findByOrderIdOrderByIdAsc(orderId);
        Map<Long, List<OrderItemEntity>> itemsBySeller = sellers.isEmpty() ? Map.of()
                : items.findBySellerOrderIdIn(sellers.stream().map(SellerOrderEntity::getId).toList())
                        .stream().collect(Collectors.groupingBy(OrderItemEntity::getSellerOrderId));
        Map<Long, VariantSnapshotResponse> displayImages = displayImages(itemsBySeller.values().stream()
                .flatMap(List::stream).toList());
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getSourceType(),
                order.getRfqIdRef(), order.getStatus(), order.getPlacedAt(), order.getSubtotal(),
                order.getDiscountTotal(), order.getShippingTotal(), order.getGrandTotal(),
                new DeliveryAddressResponse(address.getSourceAddressIdRef(), address.getRecipientName(),
                        address.getRecipientPhone(), address.getAddressLine(), address.getWard(),
                        address.getDistrict(), address.getCity()),
                sellers.stream().map(seller -> mapSeller(seller,
                        itemsBySeller.getOrDefault(seller.getId(), List.of()), address, displayImages)).toList());
    }

    @Override
    public Page<SellerOrderSummaryResponse> listSupplierOrders(Long userId, Pageable pageable) {
        if (pageable.getPageSize() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tham số size không được vượt quá 100");
        SupplierSummaryResponse supplier = supplierForUser(userId, HttpStatus.FORBIDDEN);
        Page<SellerOrderEntity> page = sellerOrders.findBySupplierIdRef(supplier.supplierId(), pageable);
        Set<Long> pending = page.isEmpty() ? Set.of() : Set.copyOf(cancellations.pendingSellerOrderIds(
                page.getContent().stream().map(SellerOrderEntity::getId).toList()));
        return page.map(seller ->
                new SellerOrderSummaryResponse(seller.getId(), seller.getOrderId(),
                        seller.getSellerOrderNo(), seller.getStoreIdRef(), seller.getStoreNameSnapshot(),
                        seller.getStatus(), seller.getTotalAmount(), pending.contains(seller.getId())));
    }

    @Override
    public SellerOrderResponse getSellerOrder(Long userId, Long customerId, Long sellerOrderId, boolean admin) {
        SellerOrderEntity seller = sellerOrders.findById(sellerOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn nhà bán"));
        CustomerOrderEntity order = orders.findById(seller.getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn nhà bán"));
        if (!admin && (customerId == null || !customerId.equals(order.getCustomerIdRef()))) {
            SupplierSummaryResponse supplier = supplierForUser(userId, HttpStatus.NOT_FOUND);
            if (!supplier.supplierId().equals(seller.getSupplierIdRef())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn nhà bán");
            }
        }
        return mapSeller(seller, items.findBySellerOrderIdIn(List.of(sellerOrderId)),
                addresses.findByOrderId(seller.getOrderId()).orElse(null), Map.of());
    }

    private SupplierSummaryResponse supplierForUser(Long userId, HttpStatus missingStatus) {
        try {
            SupplierSummaryResponse supplier = identity.getSupplierSummaryByUser(userId);
            if (supplier != null && supplier.supplierId() != null && userId.equals(supplier.userId())) {
                return supplier;
            }
        } catch (FeignException.NotFound missing) {
            // Do not expose whether another user's seller order exists.
        }
        throw new ResponseStatusException(missingStatus, "Không xác định được tài khoản nhà cung cấp");
    }

    private OrderSummaryResponse summary(CustomerOrderEntity order, List<SellerOrderEntity> sellers,
                                          Map<Long, Long> itemCounts, Set<Long> cancelled) {
        return new OrderSummaryResponse(order.getId(), order.getOrderNo(), order.getSourceType(),
                order.getRfqIdRef(), order.getStatus(), order.getPlacedAt(), order.getSubtotal(),
                order.getDiscountTotal(), order.getShippingTotal(), order.getGrandTotal(), sellers.size(),
                Math.toIntExact(sellers.stream().mapToLong(seller -> itemCounts.getOrDefault(seller.getId(), 0L)).sum()),
                sellers.stream().map(SellerOrderEntity::getStatus).distinct().toList(),
                sellers.stream().anyMatch(seller -> cancelled.contains(seller.getId())));
    }

    private Map<Long, VariantSnapshotResponse> displayImages(List<OrderItemEntity> lines) {
        List<Long> variantIds = lines.stream().filter(item -> item.getImageUrlSnapshot() == null
                        || item.getImageUrlSnapshot().isBlank())
                .map(OrderItemEntity::getVariantIdRef).filter(Objects::nonNull).distinct().toList();
        Map<Long, VariantSnapshotResponse> images = new HashMap<>();
        try {
            for (int start = 0; start < variantIds.size(); start += 100) {
                List<Long> batch = variantIds.subList(start, Math.min(start + 100, variantIds.size()));
                List<VariantSnapshotResponse> snapshots = catalog.getVariantSnapshots(new VariantSnapshotBatchRequest(batch));
                if (snapshots == null) continue;
                for (VariantSnapshotResponse snapshot : snapshots) {
                    if (snapshot != null && batch.contains(snapshot.variantId())) {
                        images.put(snapshot.variantId(), snapshot);
                    }
                }
            }
        } catch (FeignException unavailable) {
            // Display enrichment is optional; historical order details remain readable during Catalog outages.
        }
        return images;
    }

    private SellerOrderResponse mapSeller(SellerOrderEntity seller, List<OrderItemEntity> lines,
                                          DeliveryAddressEntity address, Map<Long, VariantSnapshotResponse> displayImages) {
        return new SellerOrderResponse(seller.getId(), seller.getOrderId(), seller.getSellerOrderNo(),
                seller.getStoreIdRef(), seller.getSupplierIdRef(), seller.getQuotationIdRef(),
                seller.getStoreNameSnapshot(), seller.getSupplierNameSnapshot(), seller.getStatus(),
                seller.getSubtotal(), seller.getShippingFee(), seller.getTotalAmount(),
                seller.getDepositPercent(), seller.getDepositAmount(), seller.getRemainingAmount(),
                seller.getRemainingDueAt(), seller.getDeliveryDateSnapshot(), seller.getShippingMethodSnapshot(),
                seller.getPaymentTermsSnapshot(), lines.stream().map(item -> {
                    VariantSnapshotResponse snapshot = item.getVariantIdRef() == null ? null
                            : displayImages.get(item.getVariantIdRef());
                    String imageUrl = item.getImageUrlSnapshot();
                    if ((imageUrl == null || imageUrl.isBlank()) && snapshot != null
                            && seller.getStoreIdRef().equals(snapshot.storeId())) {
                        imageUrl = snapshot.imageUrl();
                    }
                    return mapItem(item, imageUrl);
                }).toList(),
                lines.stream().map(OrderItemEntity::getDiscountAmount).filter(java.util.Objects::nonNull)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                address == null ? null : new SellerOrderResponse.DeliveryAddress(address.getRecipientName(),
                        address.getRecipientPhone(), address.getAddressLine(), address.getWard(),
                        address.getDistrict(), address.getCity()));
    }

    private OrderItemResponse mapItem(OrderItemEntity item, String imageUrl) {
        return new OrderItemResponse(item.getId(), item.getVariantIdRef(), item.getProductCodeSnapshot(),
                item.getProductNameSnapshot(), item.getSkuSnapshot(), item.getVariantNameSnapshot(),
                item.getImageUrlSnapshot(), item.getQuantity(), item.getUnitPrice(),
                item.getDiscountAmount(), item.getSubtotal(), imageUrl);
    }
}
