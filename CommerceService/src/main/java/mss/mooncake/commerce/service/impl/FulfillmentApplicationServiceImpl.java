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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FulfillmentApplicationServiceImpl implements FulfillmentApplicationService {

    private final ShipmentRepository shipmentRepository;
    private final ReviewRepository reviewRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final CustomerOrderRepository customerOrderRepository;
    private final OrderItemRepository orderItemRepository;

    public FulfillmentApplicationServiceImpl(
            ShipmentRepository shipmentRepository,
            ReviewRepository reviewRepository,
            SellerOrderRepository sellerOrderRepository,
            CustomerOrderRepository customerOrderRepository,
            OrderItemRepository orderItemRepository) {
        this.shipmentRepository = shipmentRepository;
        this.reviewRepository = reviewRepository;
        this.sellerOrderRepository = sellerOrderRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request, Long supplierId) {
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(request.sellerOrderId());

        if (supplierId != null && !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to manage shipments for this seller order");
        }

        String orderStatus = sellerOrder.getStatus();
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(orderStatus)
                || OrderStatus.AWAITING_PAYMENT.name().equalsIgnoreCase(orderStatus)
                || OrderStatus.AWAITING_DEPOSIT.name().equalsIgnoreCase(orderStatus)) {
            throw new BadRequestException("Cannot create shipment for seller order in status: " + orderStatus);
        }

        if (OrderStatus.READY_FOR_FULFILLMENT.name().equalsIgnoreCase(orderStatus)) {
            sellerOrder.setStatus(OrderStatus.PREPARING.name());
            sellerOrderRepository.save(sellerOrder);
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
        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional
    public ShipmentResponse updateShipment(Long shipmentId, UpdateShipmentRequest request, Long supplierId) {
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());

        if (supplierId != null && !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to update this shipment");
        }

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
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());

        if (supplierId != null && !sellerOrder.getSupplierIdRef().equals(supplierId)) {
            throw new ForbiddenException("You do not have permission to ship this order");
        }

        if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
            throw new ConflictException("Shipment is already delivered");
        }

        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setShippedAt(LocalDateTime.now());
        ShipmentEntity saved = shipmentRepository.save(shipment);

        sellerOrder.setStatus(OrderStatus.SHIPPING.name());
        sellerOrderRepository.save(sellerOrder);

        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional
    public ShipmentResponse markAsDelivered(Long shipmentId, Long actorId, boolean isAdmin) {
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());

        if (!isAdmin && actorId != null && !sellerOrder.getSupplierIdRef().equals(actorId)) {
            throw new ForbiddenException("You do not have permission to confirm delivery for this shipment");
        }

        if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
            return mapToShipmentResponse(shipment);
        }

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(LocalDateTime.now());
        ShipmentEntity saved = shipmentRepository.save(shipment);

        sellerOrder.setStatus(OrderStatus.DELIVERED.name());
        sellerOrderRepository.save(sellerOrder);

        return mapToShipmentResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentResponse> getShipmentsBySellerOrder(Long sellerOrderId, Long currentUserId, boolean isCustomer, boolean isAdmin) {
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        verifyReadPermission(sellerOrder, currentUserId, isCustomer, isAdmin);

        return shipmentRepository.findBySellerOrderId(sellerOrderId).stream()
                .map(this::mapToShipmentResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentResponse getShipmentById(Long shipmentId, Long currentUserId, boolean isCustomer, boolean isAdmin) {
        ShipmentEntity shipment = findShipmentOrThrow(shipmentId);
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(shipment.getSellerOrderId());
        verifyReadPermission(sellerOrder, currentUserId, isCustomer, isAdmin);

        return mapToShipmentResponse(shipment);
    }

    @Override
    @Transactional
    public SellerOrderAcceptanceResponse acceptSellerOrder(Long sellerOrderId, Long customerId) {
        SellerOrderEntity sellerOrder = findSellerOrderOrThrow(sellerOrderId);
        CustomerOrderEntity customerOrder = customerOrderRepository.findById(sellerOrder.getOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer order not found with ID: " + sellerOrder.getOrderId()));

        if (customerId != null && !customerOrder.getCustomerIdRef().equals(customerId)) {
            throw new ForbiddenException("You do not have permission to accept this seller order");
        }

        if (OrderStatus.ACCEPTED.name().equalsIgnoreCase(sellerOrder.getStatus())
                || OrderStatus.SETTLED.name().equalsIgnoreCase(sellerOrder.getStatus())) {
            return new SellerOrderAcceptanceResponse(sellerOrderId, sellerOrder.getOrderId(), sellerOrder.getStatus(), LocalDateTime.now());
        }

        if (!OrderStatus.DELIVERED.name().equalsIgnoreCase(sellerOrder.getStatus())) {
            throw new BadRequestException("Cannot accept order that is not delivered yet. Current status: " + sellerOrder.getStatus());
        }

        sellerOrder.setStatus(OrderStatus.ACCEPTED.name());
        SellerOrderEntity saved = sellerOrderRepository.save(sellerOrder);

        return new SellerOrderAcceptanceResponse(saved.getId(), saved.getOrderId(), saved.getStatus(), LocalDateTime.now());
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

        if (customerId != null && !customerOrder.getCustomerIdRef().equals(customerId)) {
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
        if (OrderStatus.CANCELLED.name().equalsIgnoreCase(status)
                || OrderStatus.AWAITING_PAYMENT.name().equalsIgnoreCase(status)
                || OrderStatus.AWAITING_DEPOSIT.name().equalsIgnoreCase(status)) {
            throw new BadRequestException("Seller order is not eligible for shipment in status: " + status);
        }
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

    private void verifyReadPermission(SellerOrderEntity sellerOrder, Long currentUserId, boolean isCustomer, boolean isAdmin) {
        if (isAdmin || currentUserId == null) {
            return;
        }
        if (isCustomer) {
            CustomerOrderEntity customerOrder = customerOrderRepository.findById(sellerOrder.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer order not found with ID: " + sellerOrder.getOrderId()));
            if (!customerOrder.getCustomerIdRef().equals(currentUserId)) {
                throw new ForbiddenException("You do not have access to view this shipment");
            }
        } else {
            if (!sellerOrder.getSupplierIdRef().equals(currentUserId)) {
                throw new ForbiddenException("You do not have access to view this shipment");
            }
        }
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
