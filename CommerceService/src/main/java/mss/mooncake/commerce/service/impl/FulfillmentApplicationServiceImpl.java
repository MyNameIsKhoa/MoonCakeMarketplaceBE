package mss.mooncake.commerce.service.impl;

import java.time.LocalDateTime;
import java.util.List;
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
import mss.mooncake.commerce.enums.OrderStatus;
import mss.mooncake.commerce.enums.ShipmentStatus;
import mss.mooncake.commerce.exception.BadRequestException;
import mss.mooncake.commerce.exception.ConflictException;
import mss.mooncake.commerce.exception.ForbiddenException;
import mss.mooncake.commerce.exception.ResourceNotFoundException;
import mss.mooncake.commerce.repository.CustomerOrderRepository;
import mss.mooncake.commerce.repository.OrderItemRepository;
import mss.mooncake.commerce.repository.ReviewRepository;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import mss.mooncake.commerce.repository.ShipmentRepository;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.repository.FinanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentApplicationServiceImpl implements FulfillmentApplicationService {

    private final ShipmentRepository shipmentRepository;
    private final ReviewRepository reviewRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final FinanceRepository finance;
    private final FinanceIntegrationService integration;

    public FulfillmentApplicationServiceImpl(
            ShipmentRepository shipmentRepository,
            ReviewRepository reviewRepository,
            SellerOrderRepository sellerOrderRepository,
            CustomerOrderRepository customerOrderRepository,
            OrderItemRepository orderItemRepository, FinanceRepository finance, FinanceIntegrationService integration) {
        this.shipmentRepository = shipmentRepository;
        this.reviewRepository = reviewRepository;
        this.sellerOrderRepository = sellerOrderRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.finance = finance;
        this.integration = integration;
    }

    @Override
    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request, Long supplierId) {
        finance.lock(request.sellerOrderId());
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(request.sellerOrderId());

        if (supplierId == null || !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to manage shipments for this seller order");
        }

        String orderStatus = sellerOrder.getStatus();
        if (!List.of("READY_FOR_FULFILLMENT", "PREPARING").contains(orderStatus)) {
            throw new BadRequestException("Cannot create shipment for seller order in status: " + orderStatus);
        }
        requirePreparationPayment(request.sellerOrderId(), false);

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
        integration.syncCustomerOrder(request.sellerOrderId());
        return mapToShipmentResponse(saved);
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

        return mapToShipmentResponse(saved);
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
        if (shipmentRepository.findBySellerOrderId(shipment.getSellerOrderId()).stream()
                .allMatch(s -> s.getStatus() == ShipmentStatus.DELIVERED)) {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            sellerOrderRepository.saveAndFlush(sellerOrder);
            var context = finance.context(shipment.getSellerOrderId());
            integration.syncFulfillment(shipment.getSellerOrderId(), context.fulfillmentVersion() + 1, true, false);
        }
        integration.syncCustomerOrder(shipment.getSellerOrderId());

        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentResponse> getShipmentsBySellerOrder(Long sellerOrderId, FinanceService.Actor actor) {
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        verifyReadPermission(sellerOrder, actor);

        return shipmentRepository.findBySellerOrderId(sellerOrderId).stream()
                .map(this::mapToShipmentResponse)
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

        sellerOrder.setStatus(OrderStatus.ACCEPTED.name());
        sellerOrder.setAcceptedAt(LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
        SellerOrderEntity saved = sellerOrderRepository.saveAndFlush(sellerOrder);
        var context = finance.context(sellerOrderId);
        integration.syncFulfillment(sellerOrderId, context.fulfillmentVersion() + 1, true, true);
        integration.syncCustomerOrder(sellerOrderId);

        return new SellerOrderAcceptanceResponse(saved.getId(), saved.getOrderId(), saved.getStatus(), saved.getAcceptedAt());
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
                LocalDateTime.now()
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
        if (!List.of("READY_FOR_FULFILLMENT", "PREPARING").contains(status)) {
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

    private void requireOpenOrder(SellerOrderEntity order) {
        if (!List.of("READY_FOR_FULFILLMENT", "PREPARING", "SHIPPING").contains(order.getStatus()))
            throw new BadRequestException("Seller order is not open for fulfillment");
    }

    private void requirePreparationPayment(Long sellerId, boolean shipping) {
        var c = finance.context(sellerId);
        long minimum = shipping || c.deposit() == null ? c.total() : c.deposit();
        if (finance.paid(sellerId) < minimum) throw new BadRequestException("Insufficient payment for fulfillment");
        if (finance.disputes(sellerId).stream().anyMatch(d -> "OPEN".equals(d.status()))
                || finance.cancellations(sellerId).stream().anyMatch(ca -> !"REJECTED".equals(ca.status())))
            throw new ConflictException("Fulfillment blocked by financial case");
    }

    private ShipmentResponse mapToShipmentResponse(ShipmentEntity entity) {
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
                entity.getStatus()
        );
    }

    private ReviewResponse mapToReviewResponse(ReviewEntity entity) {
        return new ReviewResponse(
                entity.getId(),
                entity.getOrderItemId(),
                entity.getCustomerIdRef(),
                entity.getRating(),
                entity.getComment(),
                entity.getCreatedAt()
        );
    }
}
