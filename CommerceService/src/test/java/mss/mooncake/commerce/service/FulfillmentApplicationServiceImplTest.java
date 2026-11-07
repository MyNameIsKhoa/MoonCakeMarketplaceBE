package mss.mooncake.commerce.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
import mss.mooncake.commerce.service.impl.FulfillmentApplicationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FulfillmentApplicationServiceImpl Tests (TV6)")
class FulfillmentApplicationServiceImplTest {

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private SellerOrderRepository sellerOrderRepository;

    @Mock
    private CustomerOrderRepository customerOrderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock private mss.mooncake.commerce.repository.FinanceRepository finance;
    @Mock private FinanceIntegrationService integration;

    @InjectMocks
    private FulfillmentApplicationServiceImpl fulfillmentService;

    private static final Long SELLER_ORDER_ID = 101L;
    private static final Long CUSTOMER_ORDER_ID = 201L;
    private static final Long SUPPLIER_ID = 501L;
    private static final Long OTHER_SUPPLIER_ID = 502L;
    private static final Long CUSTOMER_ID = 701L;
    private static final Long OTHER_CUSTOMER_ID = 702L;
    private static final Long ORDER_ITEM_ID = 901L;
    private static final Long SHIPMENT_ID = 301L;

    private SellerOrderEntity sellerOrder;
    private CustomerOrderEntity customerOrder;
    private OrderItemEntity orderItem;
    private ShipmentEntity shipment;

    @Test
    void plannedShipmentCannotJumpToDelivered() {
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
        assertThatThrownBy(() -> fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingSupplierNeverBypassesOwnership() {
        when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
        var request = new CreateShipmentRequest(SELLER_ORDER_ID, "STANDARD", "GHTK", "VN123456789",
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(3), BigDecimal.valueOf(30000));
        assertThatThrownBy(() -> fulfillmentService.createShipment(request, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void dualRoleSupplierReadsOwnShipmentRegardlessOfCustomerId() {
        when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
        when(shipmentRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(List.of(shipment));
        assertThat(fulfillmentService.getShipmentsBySellerOrder(SELLER_ORDER_ID,
                new FinanceService.Actor(999, OTHER_CUSTOMER_ID, SUPPLIER_ID, false))).hasSize(1);
    }

    @Test
    void cancelledOrderCannotBeShipped() {
        sellerOrder.setStatus("CANCELLED");
        when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
        when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
        assertThatThrownBy(() -> fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID))
                .isInstanceOf(BadRequestException.class);
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(finance.context(SELLER_ORDER_ID)).thenReturn(
                new mss.mooncake.commerce.repository.FinanceRepository.Context(
                        SELLER_ORDER_ID, CUSTOMER_ID, SUPPLIER_ID, 300000, null, null, true, 1, 0, false, false));
        org.mockito.Mockito.lenient().when(finance.paid(SELLER_ORDER_ID)).thenReturn(300000L);
        org.mockito.Mockito.lenient().when(shipmentRepository.findSellerOrderIdById(SHIPMENT_ID))
                .thenReturn(Optional.of(SELLER_ORDER_ID));
        sellerOrder = new SellerOrderEntity();
        sellerOrder.setOrderId(CUSTOMER_ORDER_ID);
        sellerOrder.setSupplierIdRef(SUPPLIER_ID);
        sellerOrder.setStatus(OrderStatus.READY_FOR_FULFILLMENT.name());

        customerOrder = new CustomerOrderEntity();
        customerOrder.setCustomerIdRef(CUSTOMER_ID);
        customerOrder.setStatus(OrderStatus.READY_FOR_FULFILLMENT.name());

        orderItem = new OrderItemEntity();
        orderItem.setSellerOrderId(SELLER_ORDER_ID);
        orderItem.setVariantIdRef(1001L);
        orderItem.setQuantity(2);
        orderItem.setUnitPrice(BigDecimal.valueOf(150000));
        orderItem.setSubtotal(BigDecimal.valueOf(300000));

        shipment = new ShipmentEntity(
                SELLER_ORDER_ID,
                "STANDARD",
                "GHTK",
                "VN123456789",
                LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(3),
                BigDecimal.valueOf(30000),
                ShipmentStatus.PLANNED
        );
    }

    @Nested
    @DisplayName("Shipment Creation Tests")
    class ShipmentCreationTests {

        @Test
        @DisplayName("Should create shipment successfully when supplier owns order")
        void shouldCreateShipmentSuccessfully() {
            CreateShipmentRequest request = new CreateShipmentRequest(
                    SELLER_ORDER_ID, "STANDARD", "GHTK", "VN123456789",
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(3),
                    BigDecimal.valueOf(30000)
            );

            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ShipmentResponse response = fulfillmentService.createShipment(request, SUPPLIER_ID);

            assertThat(response).isNotNull();
            assertThat(response.sellerOrderId()).isEqualTo(SELLER_ORDER_ID);
            assertThat(response.status()).isEqualTo(ShipmentStatus.PLANNED);
            assertThat(response.shippingMethod()).isEqualTo("STANDARD");
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.PREPARING.name());
            verify(sellerOrderRepository).saveAndFlush(sellerOrder);
            verify(shipmentRepository).save(any(ShipmentEntity.class));
        }

        @Test
        @DisplayName("Should throw ForbiddenException when supplier does not own order")
        void shouldThrowForbiddenWhenNotOwner() {
            CreateShipmentRequest request = new CreateShipmentRequest(
                    SELLER_ORDER_ID, "STANDARD", "GHTK", "VN123456789",
                    null, null, BigDecimal.valueOf(30000)
            );

            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));

            assertThatThrownBy(() -> fulfillmentService.createShipment(request, OTHER_SUPPLIER_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("permission");
        }

        @Test
        @DisplayName("Should throw BadRequestException when order status is awaiting payment")
        void shouldThrowBadRequestWhenStatusNotReady() {
            sellerOrder.setStatus(OrderStatus.AWAITING_PAYMENT.name());
            CreateShipmentRequest request = new CreateShipmentRequest(
                    SELLER_ORDER_ID, "STANDARD", "GHTK", "VN123456789",
                    null, null, BigDecimal.valueOf(30000)
            );

            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));

            assertThatThrownBy(() -> fulfillmentService.createShipment(request, SUPPLIER_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("AWAITING_PAYMENT");
        }
    }

    @Nested
    @DisplayName("Shipment Status Transitions Tests")
    class ShipmentTransitionTests {

        @Test
        @DisplayName("Should mark shipment as shipped and update order to SHIPPING")
        void shouldMarkShipmentAsShipped() {
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ShipmentResponse response = fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID);

            assertThat(response.status()).isEqualTo(ShipmentStatus.SHIPPED);
            assertThat(shipment.getShippedAt()).isNotNull();
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.SHIPPING.name());
            verify(sellerOrderRepository).saveAndFlush(sellerOrder);
        }

        @Test
        @DisplayName("Should mark shipment as delivered and update order to DELIVERED")
        void shouldMarkShipmentAsDelivered() {
            shipment.setStatus(ShipmentStatus.SHIPPED);
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ShipmentResponse response = fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false);

            assertThat(response.status()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(shipment.getDeliveredAt()).isNotNull();
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
            verify(sellerOrderRepository).saveAndFlush(sellerOrder);
        }
    }

    @Nested
    @DisplayName("Buyer Order Acceptance Tests")
    class OrderAcceptanceTests {

        @Test
        @DisplayName("Should accept delivered order successfully")
        void shouldAcceptDeliveredOrder() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));
            when(sellerOrderRepository.saveAndFlush(any(SellerOrderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            SellerOrderAcceptanceResponse response = fulfillmentService.acceptSellerOrder(SELLER_ORDER_ID, CUSTOMER_ID);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED.name());
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.ACCEPTED.name());
            assertThat(fulfillmentService.isEligibleForSettlement(SELLER_ORDER_ID)).isTrue();
        }

        @Test
        @DisplayName("Should throw BadRequestException if order is not delivered yet")
        void shouldThrowBadRequestIfNotDelivered() {
            sellerOrder.setStatus(OrderStatus.SHIPPING.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));

            assertThatThrownBy(() -> fulfillmentService.acceptSellerOrder(SELLER_ORDER_ID, CUSTOMER_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not delivered yet");
        }

        @Test
        @DisplayName("Should throw ForbiddenException if customer does not own order")
        void shouldThrowForbiddenIfWrongCustomer() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));

            assertThatThrownBy(() -> fulfillmentService.acceptSellerOrder(SELLER_ORDER_ID, OTHER_CUSTOMER_ID))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("Product Review Tests")
    class ReviewTests {

        @Test
        @DisplayName("Should create review successfully when item purchased and delivered")
        void shouldCreateReviewSuccessfully() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            CreateReviewRequest request = new CreateReviewRequest(ORDER_ITEM_ID, 5, "Bánh rất ngon, đóng gói đẹp!");

            when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));
            when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(false);
            when(reviewRepository.save(any(ReviewEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ReviewResponse response = fulfillmentService.createReview(request, CUSTOMER_ID);

            assertThat(response).isNotNull();
            assertThat(response.orderItemId()).isEqualTo(ORDER_ITEM_ID);
            assertThat(response.rating()).isEqualTo(5);
            assertThat(response.comment()).isEqualTo("Bánh rất ngon, đóng gói đẹp!");
            verify(reviewRepository).save(any(ReviewEntity.class));
        }

        @Test
        @DisplayName("Should throw ConflictException when item is reviewed second time")
        void shouldThrowConflictWhenReviewAlreadyExists() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            CreateReviewRequest request = new CreateReviewRequest(ORDER_ITEM_ID, 4, "Good");

            when(orderItemRepository.findById(ORDER_ITEM_ID)).thenReturn(Optional.of(orderItem));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));
            when(reviewRepository.existsByOrderItemId(ORDER_ITEM_ID)).thenReturn(true);

            assertThatThrownBy(() -> fulfillmentService.createReview(request, CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already submitted a review");
        }

        @Test
        @DisplayName("Should throw BadRequestException when rating is invalid (< 1 or > 5)")
        void shouldThrowBadRequestWhenRatingInvalid() {
            CreateReviewRequest requestZero = new CreateReviewRequest(ORDER_ITEM_ID, 0, "Bad");
            assertThatThrownBy(() -> fulfillmentService.createReview(requestZero, CUSTOMER_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("between 1 and 5");

            CreateReviewRequest requestSix = new CreateReviewRequest(ORDER_ITEM_ID, 6, "Super");
            assertThatThrownBy(() -> fulfillmentService.createReview(requestSix, CUSTOMER_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("between 1 and 5");
        }

        @Test
        @DisplayName("Should get reviews by store ID")
        void shouldGetReviewsByStore() {
            ReviewEntity reviewEntity = new ReviewEntity(ORDER_ITEM_ID, CUSTOMER_ID, 5, "Great store!", LocalDateTime.now());
            when(reviewRepository.findByStoreId(10L)).thenReturn(List.of(reviewEntity));

            List<ReviewResponse> result = fulfillmentService.getReviewsByStore(10L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).comment()).isEqualTo("Great store!");
        }
    }
}
