package mss.mooncake.commerce.service.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import mss.mooncake.commerce.dto.request.CreateReviewRequest;
import mss.mooncake.commerce.dto.request.CreateShipmentRequest;
import mss.mooncake.commerce.dto.request.UpdateShipmentRequest;
import mss.mooncake.commerce.dto.response.ReviewResponse;
import mss.mooncake.commerce.dto.response.SellerOrderAcceptanceResponse;
import mss.mooncake.commerce.dto.response.ShipmentResponse;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import mss.mooncake.commerce.entity.OrderItemEntity;
import mss.mooncake.commerce.entity.ReviewEntity;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import mss.mooncake.commerce.entity.ShipmentEntity;
import mss.mooncake.commerce.entity.ShipmentLineEntity;
import mss.mooncake.commerce.enums.OrderStatus;
import mss.mooncake.commerce.enums.ShipmentStatus;
import mss.mooncake.commerce.exception.BadRequestException;
import mss.mooncake.commerce.exception.CommerceException;
import mss.mooncake.commerce.exception.ConflictException;
import mss.mooncake.commerce.exception.ForbiddenException;
import mss.mooncake.commerce.exception.ResourceNotFoundException;
import mss.mooncake.commerce.repository.CustomerOrderRepository;
import mss.mooncake.commerce.repository.OrderItemRepository;
import mss.mooncake.commerce.repository.ReviewRepository;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import mss.mooncake.commerce.repository.ShipmentRepository;
import mss.mooncake.commerce.repository.ShipmentLineRepository;
import mss.mooncake.commerce.dto.response.ShipmentItemProgressResponse;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.notification.CommerceNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentApplicationServiceImpl implements FulfillmentApplicationService {

    private static final List<String> UNRESOLVED_DISPUTES = List.of("OPEN", "ADMIN_REVIEW", "REFUND_PENDING");
    private final ShipmentRepository shipmentRepository;
    private final ReviewRepository reviewRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShipmentLineRepository shipmentLineRepository;
    private final FinanceRepository finance;
    private final FinanceIntegrationService integration;
    private final CommerceNotifier notifier;

    // Direct construction remains useful for tests that do not check notifications.
    public FulfillmentApplicationServiceImpl(
            ShipmentRepository shipmentRepository,
            ReviewRepository reviewRepository,
            SellerOrderRepository sellerOrderRepository,
            CustomerOrderRepository customerOrderRepository,
            OrderItemRepository orderItemRepository, ShipmentLineRepository shipmentLineRepository,
            FinanceRepository finance, FinanceIntegrationService integration) {
        this(shipmentRepository, reviewRepository, sellerOrderRepository, customerOrderRepository, orderItemRepository,
                shipmentLineRepository, finance, integration, CommerceNotifier.disabled());
    }

    @Autowired
    public FulfillmentApplicationServiceImpl(
            ShipmentRepository shipmentRepository,
            ReviewRepository reviewRepository,
            SellerOrderRepository sellerOrderRepository,
            CustomerOrderRepository customerOrderRepository,
            OrderItemRepository orderItemRepository, ShipmentLineRepository shipmentLineRepository,
            FinanceRepository finance, FinanceIntegrationService integration, CommerceNotifier notifier) {
        this.shipmentRepository = shipmentRepository;
        this.reviewRepository = reviewRepository;
        this.sellerOrderRepository = sellerOrderRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.shipmentLineRepository = shipmentLineRepository;
        this.finance = finance;
        this.integration = integration;
        this.notifier = notifier;
    }

    @Override
    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request, Long supplierId) {
        requireDeliveryNotBeforeShip(request.expectedShipAt(), request.expectedDeliveryAt());
        finance.lock(request.sellerOrderId());
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(request.sellerOrderId());

        if (supplierId == null || !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to manage shipments for this seller order");
        }

        String orderStatus = sellerOrder.getStatus();
        if (!List.of("READY_FOR_FULFILLMENT", "PREPARING", "SHIPPING").contains(orderStatus)) {
            throw new BadRequestException("Cannot create shipment for seller order in status: " + orderStatus);
        }
        requirePreparationPayment(request.sellerOrderId(), false);
        List<OrderItemEntity> items = orderItemRepository.findBySellerOrderId(request.sellerOrderId());
        Map<Long, Integer> quantities = shipmentQuantities(request.lines(), items,
                batches(request.sellerOrderId()).units(status -> status != ShipmentStatus.CANCELLED));

        if (OrderStatus.READY_FOR_FULFILLMENT.name().equalsIgnoreCase(orderStatus)) {
            sellerOrder.setStatus(OrderStatus.PREPARING.name());
            sellerOrderRepository.saveAndFlush(sellerOrder);
        }

        ShipmentEntity shipment = new ShipmentEntity(
                request.sellerOrderId(),
                request.shippingMethod(),
                request.provider(),
                request.trackingCode(),
                request.expectedShipAt(),
                request.expectedDeliveryAt(),
                request.shippingFee(),
                ShipmentStatus.PLANNED
        );

        ShipmentEntity saved = shipmentRepository.save(shipment);
        List<ShipmentLineEntity> lines = quantities.entrySet().stream()
                .map(line -> new ShipmentLineEntity(saved.getId(), line.getKey(), line.getValue())).toList();
        shipmentLineRepository.saveAll(lines);
        integration.syncCustomerOrder(request.sellerOrderId());
        return toResponse(saved, lines, itemsById(items));
    }

    /**
     * Units per order item for a new shipment, in order-item order. Without lines the shipment takes every unit not
     * yet in a non-cancelled shipment; with lines each item must belong to the order and fit in what remains.
     */
    private static Map<Long, Integer> shipmentQuantities(List<CreateShipmentRequest.Line> requested,
            List<OrderItemEntity> items, Map<Long, Integer> allocated) {
        Map<Long, Integer> remaining = new LinkedHashMap<>();
        for (OrderItemEntity item : items) {
            remaining.put(item.getId(), item.getQuantity() - allocated.getOrDefault(item.getId(), 0));
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        if (requested == null || requested.isEmpty()) {
            remaining.forEach((itemId, units) -> { if (units > 0) quantities.put(itemId, units); });
            if (quantities.isEmpty()) {
                throw new CommerceException(HttpStatus.CONFLICT, "NOTHING_LEFT_TO_SHIP",
                        "Mọi sản phẩm của đơn đã được xếp vào các kiện hàng.");
            }
            return quantities;
        }
        Map<Long, Integer> byItem = new HashMap<>();
        for (CreateShipmentRequest.Line line : requested) {
            if (line == null || line.quantity() == null || line.quantity() <= 0) {
                throw new CommerceException(HttpStatus.BAD_REQUEST, "SHIPMENT_LINE_INVALID_QUANTITY",
                        "Số lượng của mỗi sản phẩm trong kiện phải lớn hơn 0.");
            }
            if (line.orderItemId() == null || !remaining.containsKey(line.orderItemId())) {
                throw new CommerceException(HttpStatus.BAD_REQUEST, "SHIPMENT_LINE_ITEM_NOT_IN_ORDER",
                        "Sản phẩm không thuộc đơn hàng này.");
            }
            if (byItem.putIfAbsent(line.orderItemId(), line.quantity()) != null) {
                throw new CommerceException(HttpStatus.BAD_REQUEST, "SHIPMENT_LINE_DUPLICATE_ITEM",
                        "Mỗi sản phẩm chỉ được khai báo một lần trong một kiện.");
            }
            if (line.quantity() > remaining.get(line.orderItemId())) {
                throw new CommerceException(HttpStatus.CONFLICT, "SHIPMENT_QUANTITY_EXCEEDS_REMAINING",
                        "Số lượng giao vượt quá số lượng còn lại chưa xếp kiện của sản phẩm (còn "
                                + Math.max(0, remaining.get(line.orderItemId())) + ").");
            }
        }
        remaining.keySet().stream().filter(byItem::containsKey).forEach(itemId -> quantities.put(itemId, byItem.get(itemId)));
        return quantities;
    }

    @Override
    @Transactional
    public ShipmentResponse updateShipment(Long shipmentId, UpdateShipmentRequest request, Long supplierId) {
        lockShipmentOrder(shipmentId);
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());

        if (supplierId == null || !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to update this shipment");
        }
        requireOpenOrder(sellerOrder);

        if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
            throw new ConflictException("Cannot modify a shipment that has already been delivered");
        }
        if (shipment.getStatus() == ShipmentStatus.CANCELLED) {
            throw new ConflictException("Cannot modify a cancelled shipment");
        }
        requireDeliveryNotBeforeShip(shipment.getExpectedShipAt(), request.expectedDeliveryAt());

        if (request.shippingMethod() != null && !request.shippingMethod().isBlank()) {
            shipment.setShippingMethod(request.shippingMethod());
        }
        if (request.provider() != null) {
            shipment.setProvider(request.provider());
        }
        if (request.trackingCode() != null) {
            shipment.setTrackingCode(request.trackingCode());
        }
        if (request.expectedDeliveryAt() != null) {
            shipment.setExpectedDeliveryAt(request.expectedDeliveryAt());
        }

        ShipmentEntity saved = shipmentRepository.save(shipment);
        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional
    public ShipmentResponse markAsShipped(Long shipmentId, Long supplierId) {
        lockShipmentOrder(shipmentId);
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());

        if (supplierId == null || !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to ship this order");
        }
        if (shipment.getStatus() == ShipmentStatus.SHIPPED || shipment.getStatus() == ShipmentStatus.DELIVERED)
            return mapToShipmentResponse(shipment);
        requireOpenOrder(sellerOrder);
        requirePreparationPayment(shipment.getSellerOrderId(), true);
        if (!List.of(ShipmentStatus.PLANNED, ShipmentStatus.READY_TO_SHIP).contains(shipment.getStatus()))
            throw new ConflictException("Shipment is not ready to ship");

        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setShippedAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        ShipmentEntity saved = shipmentRepository.save(shipment);

        sellerOrder.setStatus(OrderStatus.SHIPPING.name());
        sellerOrderRepository.saveAndFlush(sellerOrder);
        integration.syncCustomerOrder(shipment.getSellerOrderId());
        notifier.shipmentShipped(shipment.getSellerOrderId(), shipmentId, shipment.getProvider(),
                shipment.getTrackingCode());

        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional
    public ShipmentResponse cancelShipment(Long shipmentId, Long supplierId) {
        lockShipmentOrder(shipmentId);
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());
        if (supplierId == null || !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to cancel this shipment");
        }
        if (shipment.getStatus() == ShipmentStatus.CANCELLED) return mapToShipmentResponse(shipment);
        if (shipment.getStatus() != ShipmentStatus.PLANNED) {
            throw new ConflictException("Only a planned shipment can be cancelled");
        }
        shipment.setStatus(ShipmentStatus.CANCELLED);
        return mapToShipmentResponse(shipmentRepository.save(shipment));
    }

    @Override
    @Transactional
    public ShipmentResponse markAsDelivered(Long shipmentId, Long actorId, boolean isAdmin) {
        lockShipmentOrder(shipmentId);
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());

        if (!isAdmin && (actorId == null || !sellerOrder.getSupplierIdRef().equals(actorId))) {
            throw new ForbiddenException("You do not have permission to confirm delivery for this shipment");
        }

        if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
            return mapToShipmentResponse(shipment);
        }
        requireOpenOrder(sellerOrder);
        if (shipment.getStatus() != ShipmentStatus.SHIPPED) {
            throw new BadRequestException("Only a shipped shipment can be delivered");
        }

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(LocalDateTime.now(java.time.ZoneOffset.UTC));
        ShipmentEntity saved = shipmentRepository.save(shipment);

        shipmentRepository.flush();
        boolean orderDelivered = everyUnitDelivered(shipment.getSellerOrderId());
        if (orderDelivered) {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            sellerOrderRepository.saveAndFlush(sellerOrder);
            var context = finance.context(shipment.getSellerOrderId());
            integration.syncFulfillment(shipment.getSellerOrderId(), context.fulfillmentVersion() + 1, true, false);
        }
        integration.syncCustomerOrder(shipment.getSellerOrderId());
        notifier.shipmentDelivered(shipment.getSellerOrderId(), shipmentId, orderDelivered);

        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentResponse> getShipmentsBySellerOrder(Long sellerOrderId, FinanceService.Actor actor) {
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        verifyReadPermission(sellerOrder, actor);

        Batches batches = batches(sellerOrderId);
        var items = itemsById(orderItemRepository.findBySellerOrderId(sellerOrderId));
        return batches.shipments().stream()
                .map(s -> toResponse(s, batches.linesOf(s), items))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentResponse getShipmentById(Long shipmentId, FinanceService.Actor actor) {
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());
        verifyReadPermission(sellerOrder, actor);

        return mapToShipmentResponse(shipment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentItemProgressResponse> getShipmentItemProgress(Long sellerOrderId, FinanceService.Actor actor) {
        verifyReadPermission(findSellerOrderOrThrow(sellerOrderId), actor);
        Batches batches = batches(sellerOrderId);
        Map<Long, Integer> allocated = batches.units(status -> status != ShipmentStatus.CANCELLED);
        Map<Long, Integer> shipped = batches.units(status -> status == ShipmentStatus.SHIPPED
                || status == ShipmentStatus.DELIVERED);
        Map<Long, Integer> delivered = batches.units(status -> status == ShipmentStatus.DELIVERED);
        return orderItemRepository.findBySellerOrderId(sellerOrderId).stream().map(item -> {
            int inShipments = allocated.getOrDefault(item.getId(), 0);
            return new ShipmentItemProgressResponse(item.getId(), item.getProductNameSnapshot(),
                    item.getVariantNameSnapshot(), item.getSkuSnapshot(), item.getQuantity(), inShipments,
                    shipped.getOrDefault(item.getId(), 0), delivered.getOrDefault(item.getId(), 0),
                    Math.max(0, item.getQuantity() - inShipments));
        }).toList();
    }

    @Override
    @Transactional
    public SellerOrderAcceptanceResponse acceptSellerOrder(Long sellerOrderId, Long customerId) {
        finance.lock(sellerOrderId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        CustomerOrderEntity customerOrder = customerOrderRepository.findById(sellerOrder.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer order not found with ID: " + sellerOrder.getOrderId()));

        if (customerId == null || !customerOrder.getCustomerIdRef().equals(customerId)) {
            throw new ForbiddenException("You do not have permission to accept this seller order");
        }

        if (OrderStatus.ACCEPTED.name().equalsIgnoreCase(sellerOrder.getStatus())
                || OrderStatus.SETTLED.name().equalsIgnoreCase(sellerOrder.getStatus())) {
            return new SellerOrderAcceptanceResponse(sellerOrderId, sellerOrder.getOrderId(), sellerOrder.getStatus(), sellerOrder.getAcceptedAt());
        }

        if (!OrderStatus.DELIVERED.name().equalsIgnoreCase(sellerOrder.getStatus())) {
            throw new BadRequestException("Cannot accept order that is not delivered yet. Current status: " + sellerOrder.getStatus());
        }
        if (hasUnresolvedDispute(sellerOrderId)) {
            throw new ConflictException("Cannot accept an order while its dispute is unresolved");
        }

        sellerOrder.setStatus(OrderStatus.ACCEPTED.name());
        sellerOrder.setAcceptedAt(LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
        SellerOrderEntity saved = sellerOrderRepository.saveAndFlush(sellerOrder);
        var context = finance.context(sellerOrderId);
        integration.syncFulfillment(sellerOrderId, context.fulfillmentVersion() + 1, true, true);
        integration.syncCustomerOrder(sellerOrderId);
        notifier.orderAccepted(sellerOrderId, false);

        return new SellerOrderAcceptanceResponse(saved.getId(), saved.getOrderId(), saved.getStatus(), saved.getAcceptedAt());
    }

    @Override
    @Transactional
    public boolean autoAcceptDelivered(Long sellerOrderId, java.time.Instant deliveredBefore) {
        finance.lock(sellerOrderId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        var context = finance.context(sellerOrderId);
        var deliveredAt = finance.deliveredAt(sellerOrderId);
        // Re-checked under the order lock: the buyer may have accepted or disputed since the job listed it.
        if (!OrderStatus.DELIVERED.name().equals(sellerOrder.getStatus()) || !context.delivered() || context.received()
                || deliveredAt == null || deliveredAt.isAfter(deliveredBefore)
                || finance.disputes(sellerOrderId).stream().anyMatch(d -> UNRESOLVED_DISPUTES.contains(d.status())
                        || (d.openedAt() != null && !d.openedAt().isBefore(deliveredAt)))) {
            return false;
        }
        sellerOrder.setStatus(OrderStatus.ACCEPTED.name());
        sellerOrder.setAcceptedAt(LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
        sellerOrderRepository.saveAndFlush(sellerOrder);
        integration.syncFulfillment(sellerOrderId, context.fulfillmentVersion() + 1, true, true);
        integration.syncCustomerOrder(sellerOrderId);
        finance.event(sellerOrderId, "ORDER_AUTO_ACCEPTED", sellerOrderId, "SYSTEM",
                "Dispute window passed without buyer acceptance or dispute");
        notifier.orderAccepted(sellerOrderId, true);
        return true;
    }

    @Override
    @Transactional
    public ReviewResponse createReview(CreateReviewRequest request, Long customerId) {
        if (request.rating() < 1 || request.rating() > 5) {
            throw new BadRequestException("Rating must be between 1 and 5 stars");
        }

        OrderItemEntity orderItem = orderItemRepository.findById(request.orderItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Order item not found with ID: " + request.orderItemId()));

        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(orderItem.getSellerOrderId());
        CustomerOrderEntity customerOrder = customerOrderRepository.findById(sellerOrder.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer order not found with ID: " + sellerOrder.getOrderId()));

        if (customerId == null || !customerOrder.getCustomerIdRef().equals(customerId)) {
            throw new ForbiddenException("You can only review items that you have purchased");
        }

        String orderStatus = sellerOrder.getStatus();
        if (!OrderStatus.DELIVERED.name().equalsIgnoreCase(orderStatus)
                && !OrderStatus.ACCEPTED.name().equalsIgnoreCase(orderStatus)
                && !OrderStatus.SETTLED.name().equalsIgnoreCase(orderStatus)) {
            throw new BadRequestException("Cannot review item from an order that is not delivered or accepted yet. Status: " + orderStatus);
        }

        if (reviewRepository.existsByOrderItemId(request.orderItemId())) {
            throw new ConflictException("You have already submitted a review for this order item");
        }

        ReviewEntity review = new ReviewEntity(
                request.orderItemId(),
                customerId,
                request.rating(),
                request.comment(),
                LocalDateTime.now(java.time.ZoneOffset.UTC)
        );

        ReviewEntity saved = reviewRepository.save(review);
        return mapToReviewResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewResponse> getReviewsByVariant(Long variantId) {
        if (variantId == null) {
            return reviewRepository.findAll().stream().map(this::mapToReviewResponse).toList();
        }
        return reviewRepository.findByVariantId(variantId).stream()
                .map(this::mapToReviewResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewResponse> getReviewsByStore(Long storeId) {
        if (storeId == null) {
            return reviewRepository.findAll().stream().map(this::mapToReviewResponse).toList();
        }
        return reviewRepository.findByStoreId(storeId).stream()
                .map(this::mapToReviewResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewResponse> getMyReviews(Long customerId) {
        if (customerId == null) {
            return List.of();
        }
        return reviewRepository.findByCustomerIdRef(customerId).stream()
                .map(this::mapToReviewResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewResponse getReviewById(Long reviewId) {
        ReviewEntity review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found with ID: " + reviewId));
        return mapToReviewResponse(review);
    }

    @Override
    public void assertEligibleForShipment(Long sellerOrderId) {
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        String status = sellerOrder.getStatus();
        if (!List.of("READY_FOR_FULFILLMENT", "PREPARING", "SHIPPING").contains(status)) {
            throw new BadRequestException("Seller order is not eligible for shipment in status: " + status);
        }
        requirePreparationPayment(sellerOrderId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEligibleForSettlement(Long sellerOrderId) {
        return sellerOrderRepository.findById(sellerOrderId)
                .map(so -> OrderStatus.ACCEPTED.name().equalsIgnoreCase(so.getStatus()))
                .orElse(false);
    }

    // --- Private Helper Methods ---

    private SellerOrderEntity findSellerOrderOrThrow(Long sellerOrderId) {
        return sellerOrderRepository.findById(sellerOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Seller order not found with ID: " + sellerOrderId));
    }

    private ShipmentEntity findShipmentOrThrow(Long shipmentId) {
        return shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found with ID: " + shipmentId));
    }

    private void verifyReadPermission(SellerOrderEntity sellerOrder, FinanceService.Actor actor) {
        if (actor == null) throw new ForbiddenException("Authentication required");
        if (actor.admin() || sellerOrder.getSupplierIdRef().equals(actor.supplierId())) return;
        var parent = customerOrderRepository.findById(sellerOrder.getOrderId()).orElseThrow();
        if (!parent.getCustomerIdRef().equals(actor.customerId()))
            throw new ForbiddenException("You do not have access to view this shipment");
    }

    private void lockShipmentOrder(Long shipmentId) {
        Long sellerId = shipmentRepository.findSellerOrderIdById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment not found: " + shipmentId));
        finance.lock(sellerId);
    }

    private static void requireDeliveryNotBeforeShip(LocalDateTime shipAt, LocalDateTime deliveryAt) {
        if (shipAt != null && deliveryAt != null && deliveryAt.isBefore(shipAt))
            throw new BadRequestException("expectedDeliveryAt must not be before expectedShipAt");
    }

    private void requireOpenOrder(SellerOrderEntity order) {
        if (!List.of("READY_FOR_FULFILLMENT", "PREPARING", "SHIPPING").contains(order.getStatus()))
            throw new BadRequestException("Seller order is not open for fulfillment");
    }

    private void requirePreparationPayment(Long sellerId, boolean shipping) {
        var c = finance.context(sellerId);
        long minimum = shipping || c.deposit() == null ? c.total() : c.deposit();
        // Money already returned to the buyer no longer pays for fulfillment.
        if (finance.paid(sellerId) - finance.refunded(sellerId) < minimum)
            throw new BadRequestException("Insufficient payment for fulfillment");
        if (hasUnresolvedDispute(sellerId)
                || finance.cancellations(sellerId).stream().anyMatch(ca -> !"REJECTED".equals(ca.status()))
                || finance.refunds(sellerId).stream().anyMatch(r -> "MANUAL_REVIEW".equals(r.status())))
            throw new ConflictException("Fulfillment blocked by financial case");
    }

    /** OPEN, ADMIN_REVIEW and REFUND_PENDING disputes all still wait for a decision or its refund. */
    private boolean hasUnresolvedDispute(Long sellerId) {
        return finance.disputes(sellerId).stream().anyMatch(d -> UNRESOLVED_DISPUTES.contains(d.status()));
    }

    /** Shipments of a seller order with their lines; a legacy shipment (created before lines existed) has none. */
    private record Batches(List<ShipmentEntity> shipments, Map<Long, List<ShipmentLineEntity>> lines) {
        List<ShipmentLineEntity> linesOf(ShipmentEntity shipment) {
            return shipment.getId() == null ? List.of() : lines.getOrDefault(shipment.getId(), List.of());
        }

        /** Units per order item carried by the shipments whose status matches. */
        Map<Long, Integer> units(Predicate<ShipmentStatus> status) {
            Map<Long, Integer> total = new HashMap<>();
            for (ShipmentEntity shipment : shipments) {
                if (!status.test(shipment.getStatus())) continue;
                for (ShipmentLineEntity line : linesOf(shipment)) {
                    total.merge(line.getOrderItemId(), line.getQuantity(), Integer::sum);
                }
            }
            return total;
        }

        boolean hasLegacyShipment() {
            return shipments.stream().anyMatch(s -> s.getStatus() != ShipmentStatus.CANCELLED && linesOf(s).isEmpty());
        }
    }

    private Batches batches(Long sellerOrderId) {
        List<ShipmentEntity> shipments = shipmentRepository.findBySellerOrderId(sellerOrderId);
        List<Long> ids = shipments.stream().map(ShipmentEntity::getId).filter(Objects::nonNull).toList();
        List<ShipmentLineEntity> lines = ids.isEmpty() ? List.of() : shipmentLineRepository.findByShipmentIdIn(ids);
        return new Batches(shipments, lines.stream().collect(Collectors.groupingBy(ShipmentLineEntity::getShipmentId)));
    }

    /**
     * DELIVERED once every ordered unit is in a delivered shipment. While a non-cancelled legacy shipment without
     * lines exists its content is unknown, so the old rule applies: every non-cancelled shipment is delivered.
     * A withdrawn (cancelled) batch never ships, so it cannot hold the order back either way.
     */
    private boolean everyUnitDelivered(Long sellerOrderId) {
        Batches batches = batches(sellerOrderId);
        if (batches.hasLegacyShipment()) {
            return batches.shipments().stream()
                    .filter(s -> s.getStatus() != ShipmentStatus.CANCELLED)
                    .allMatch(s -> s.getStatus() == ShipmentStatus.DELIVERED);
        }
        Map<Long, Integer> delivered = batches.units(status -> status == ShipmentStatus.DELIVERED);
        return orderItemRepository.findBySellerOrderId(sellerOrderId).stream()
                .allMatch(item -> delivered.getOrDefault(item.getId(), 0) >= item.getQuantity());
    }

    private static Map<Long, OrderItemEntity> itemsById(List<OrderItemEntity> items) {
        Map<Long, OrderItemEntity> byId = new HashMap<>();
        items.forEach(item -> byId.put(item.getId(), item));
        return byId;
    }

    private ShipmentResponse mapToShipmentResponse(ShipmentEntity entity) {
        List<ShipmentLineEntity> lines = entity.getId() == null ? List.of()
                : shipmentLineRepository.findByShipmentIdIn(List.of(entity.getId())).stream()
                        .filter(line -> entity.getId().equals(line.getShipmentId())).toList();
        return toResponse(entity, lines, lines.isEmpty() ? Map.of()
                : itemsById(orderItemRepository.findBySellerOrderId(entity.getSellerOrderId())));
    }

    private static ShipmentResponse toResponse(ShipmentEntity entity, List<ShipmentLineEntity> lines,
            Map<Long, OrderItemEntity> items) {
        return new ShipmentResponse(
                entity.getId(),
                entity.getSellerOrderId(),
                entity.getShippingMethod(),
                entity.getProvider(),
                entity.getTrackingCode(),
                entity.getExpectedShipAt(),
                entity.getShippedAt(),
                entity.getExpectedDeliveryAt(),
                entity.getDeliveredAt(),
                entity.getShippingFee(),
                entity.getStatus(),
                lines.stream().map(line -> {
                    OrderItemEntity item = items.get(line.getOrderItemId());
                    return new ShipmentResponse.Line(line.getOrderItemId(),
                            item == null ? null : item.getProductNameSnapshot(),
                            item == null ? null : item.getVariantNameSnapshot(),
                            item == null ? null : item.getSkuSnapshot(), line.getQuantity());
                }).toList()
        );
    }

    private ReviewResponse mapToReviewResponse(ReviewEntity entity) {
        OrderItemEntity item = orderItemRepository != null && entity.getOrderItemId() != null
                ? orderItemRepository.findById(entity.getOrderItemId()).orElse(null)
                : null;
        String productName = item != null ? item.getProductNameSnapshot() : null;
        String variantName = item != null ? item.getVariantNameSnapshot() : null;
        String displayName = entity.getCustomerIdRef() != null
                ? "Khách hàng #" + entity.getCustomerIdRef()
                : "Khách hàng ẩn danh";
        return new ReviewResponse(
                entity.getId(),
                entity.getOrderItemId(),
                entity.getCustomerIdRef(),
                entity.getRating(),
                entity.getComment(),
                entity.getCreatedAt(),
                productName,
                variantName,
                displayName
        );
    }
}
