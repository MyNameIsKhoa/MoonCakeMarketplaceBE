package mss.mooncake.commerce.service.impl;

import feign.FeignException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mss.mooncake.commerce.client.IdentityServiceClient;
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

    public OrderQueryServiceImpl(CustomerOrderRepository orders, DeliveryAddressRepository addresses,
                                 SellerOrderRepository sellerOrders, OrderItemRepository items,
                                 IdentityServiceClient identity, CancellationRepository cancellations) {
        this.orders = orders;
        this.addresses = addresses;
        this.sellerOrders = sellerOrders;
        this.items = items;
        this.identity = identity;
        this.cancellations = cancellations;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> listCustomerOrders(Long customerId, Pageable pageable) {
        if (pageable.getPageSize() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be <= 100");
        return orders.findByCustomerIdRef(customerId, pageable).map(this::summary);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailResponse getCustomerOrder(Long customerId, Long orderId) {
        CustomerOrderEntity order = orders.findByIdAndCustomerIdRef(orderId, customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        DeliveryAddressEntity address = addresses.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Order address snapshot missing"));
        List<SellerOrderEntity> sellers = sellerOrders.findByOrderIdOrderByIdAsc(orderId);
        Map<Long, List<OrderItemEntity>> itemsBySeller = sellers.isEmpty() ? Map.of()
                : items.findBySellerOrderIdIn(sellers.stream().map(SellerOrderEntity::getId).toList())
                        .stream().collect(Collectors.groupingBy(OrderItemEntity::getSellerOrderId));
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getSourceType(),
                order.getRfqIdRef(), order.getStatus(), order.getPlacedAt(), order.getSubtotal(),
                order.getDiscountTotal(), order.getShippingTotal(), order.getGrandTotal(),
                new DeliveryAddressResponse(address.getSourceAddressIdRef(), address.getRecipientName(),
                        address.getRecipientPhone(), address.getAddressLine(), address.getWard(),
                        address.getDistrict(), address.getCity()),
                sellers.stream().map(seller -> mapSeller(seller,
                        itemsBySeller.getOrDefault(seller.getId(), List.of()))).toList());
    }

    @Override
    public Page<SellerOrderSummaryResponse> listSupplierOrders(Long userId, Pageable pageable) {
        if (pageable.getPageSize() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be <= 100");
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
    public SellerOrderResponse getSellerOrder(Long userId, Long customerId, Long sellerOrderId) {
        SellerOrderEntity seller = sellerOrders.findById(sellerOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller order not found"));
        CustomerOrderEntity order = orders.findById(seller.getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller order not found"));
        if (customerId == null || !customerId.equals(order.getCustomerIdRef())) {
            SupplierSummaryResponse supplier = supplierForUser(userId, HttpStatus.NOT_FOUND);
            if (!supplier.supplierId().equals(seller.getSupplierIdRef())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller order not found");
            }
        }
        return mapSeller(seller, items.findBySellerOrderIdIn(List.of(sellerOrderId)));
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
        throw new ResponseStatusException(missingStatus, "Supplier context unavailable");
    }

    private OrderSummaryResponse summary(CustomerOrderEntity order) {
        return new OrderSummaryResponse(order.getId(), order.getOrderNo(), order.getSourceType(),
                order.getRfqIdRef(), order.getStatus(), order.getPlacedAt(), order.getSubtotal(),
                order.getDiscountTotal(), order.getShippingTotal(), order.getGrandTotal());
    }

    private SellerOrderResponse mapSeller(SellerOrderEntity seller, List<OrderItemEntity> lines) {
        return new SellerOrderResponse(seller.getId(), seller.getOrderId(), seller.getSellerOrderNo(),
                seller.getStoreIdRef(), seller.getSupplierIdRef(), seller.getQuotationIdRef(),
                seller.getStoreNameSnapshot(), seller.getSupplierNameSnapshot(), seller.getStatus(),
                seller.getSubtotal(), seller.getShippingFee(), seller.getTotalAmount(),
                seller.getDepositPercent(), seller.getDepositAmount(), seller.getRemainingAmount(),
                seller.getRemainingDueAt(), seller.getDeliveryDateSnapshot(), seller.getShippingMethodSnapshot(),
                seller.getPaymentTermsSnapshot(), lines.stream().map(this::mapItem).toList());
    }

    private OrderItemResponse mapItem(OrderItemEntity item) {
        return new OrderItemResponse(item.getId(), item.getVariantIdRef(), item.getProductCodeSnapshot(),
                item.getProductNameSnapshot(), item.getSkuSnapshot(), item.getVariantNameSnapshot(),
                item.getImageUrlSnapshot(), item.getQuantity(), item.getUnitPrice(),
                item.getDiscountAmount(), item.getSubtotal());
    }
}
