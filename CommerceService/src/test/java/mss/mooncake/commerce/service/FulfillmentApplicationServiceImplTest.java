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
import mss.mooncake.commerce.repository.ShipmentLineRepository;
import mss.mooncake.commerce.entity.ShipmentLineEntity;
import mss.mooncake.commerce.dto.response.ShipmentItemProgressResponse;
import mss.mooncake.commerce.exception.CommerceException;
import mss.mooncake.commerce.service.impl.FulfillmentApplicationServiceImpl;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.commerce.notification.CommerceNotifier;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
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
    @Mock private ShipmentLineRepository shipmentLineRepository;
    @Mock private mss.mooncake.commerce.repository.FinanceRepository finance;
    @Mock private FinanceIntegrationService integration;
    @Mock private CommerceNotifier notifier;

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
        ReflectionTestUtils.setField(orderItem, "id", ORDER_ITEM_ID);
        orderItem.setSellerOrderId(SELLER_ORDER_ID);
        orderItem.setVariantIdRef(1001L);
        orderItem.setProductNameSnapshot("Hộp Trăng Vàng Hoàng Kim");
        orderItem.setVariantNameSnapshot("Hộp 4 bánh - Ít ngọt");
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
            when(orderItemRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(List.of(orderItem));
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

        @Test
        @DisplayName("Should allow creating additional shipment when order is already SHIPPING (multi-batch B2B)")
        void shouldAllowAdditionalShipmentWhileShipping() {
            sellerOrder.setStatus(OrderStatus.SHIPPING.name());
            CreateShipmentRequest request = new CreateShipmentRequest(
                    SELLER_ORDER_ID, "EXPRESS", "GHN", "GHN998877",
                    LocalDateTime.now().plusHours(4), LocalDateTime.now().plusDays(1),
                    BigDecimal.ZERO
            );

            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(orderItemRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(List.of(orderItem));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ShipmentResponse response = fulfillmentService.createShipment(request, SUPPLIER_ID);

            assertThat(response).isNotNull();
            assertThat(response.status()).isEqualTo(ShipmentStatus.PLANNED);
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.SHIPPING.name());
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

        @Test
        @DisplayName("Should keep sellerOrder in SHIPPING when only 1 of 2 shipments is delivered")
        void shouldNotMarkOrderDeliveredUntilAllShipmentsDelivered() {
            sellerOrder.setStatus(OrderStatus.SHIPPING.name());
            shipment.setStatus(ShipmentStatus.SHIPPED);
            ShipmentEntity secondShipment = new ShipmentEntity(
                    SELLER_ORDER_ID, "STANDARD", "GHN", "GHN-BATCH-2",
                    null, null, BigDecimal.ZERO, ShipmentStatus.SHIPPED
            );
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(shipmentRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(List.of(shipment, secondShipment));

            ShipmentResponse response = fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false);

            assertThat(response.status()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.SHIPPING.name());
        }
    }

    @Nested
    @DisplayName("Shipment lines: one seller order split into several shipments by item")
    class ShipmentLineTests {
        private static final Long SECOND_ITEM_ID = 902L;
        private static final Long NEW_SHIPMENT_ID = 777L;

        @BeforeEach
        void orderWithTwoItems() {
            // ORDER_ITEM_ID: 2 units; SECOND_ITEM_ID: 3 units.
            OrderItemEntity second = new OrderItemEntity();
            ReflectionTestUtils.setField(second, "id", SECOND_ITEM_ID);
            second.setSellerOrderId(SELLER_ORDER_ID);
            second.setProductNameSnapshot("Hộp Nguyệt Quế");
            second.setVariantNameSnapshot("Hộp 6 bánh");
            second.setSkuSnapshot("NQ-6");
            second.setQuantity(3);
            ReflectionTestUtils.setField(shipment, "id", SHIPMENT_ID);
            org.mockito.Mockito.lenient().when(sellerOrderRepository.findById(SELLER_ORDER_ID))
                    .thenReturn(Optional.of(sellerOrder));
            org.mockito.Mockito.lenient().when(orderItemRepository.findBySellerOrderId(SELLER_ORDER_ID))
                    .thenReturn(List.of(orderItem, second));
            org.mockito.Mockito.lenient().when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(call -> {
                ShipmentEntity saved = call.getArgument(0);
                if (saved.getId() == null) ReflectionTestUtils.setField(saved, "id", NEW_SHIPMENT_ID);
                return saved;
            });
        }

        private CreateShipmentRequest request(List<CreateShipmentRequest.Line> lines) {
            return new CreateShipmentRequest(SELLER_ORDER_ID, "STANDARD", "GHTK", "VN-SPLIT",
                    null, null, BigDecimal.ZERO, lines);
        }

        private ShipmentEntity batch(Long id, ShipmentStatus status) {
            ShipmentEntity entity = new ShipmentEntity(SELLER_ORDER_ID, "STANDARD", "GHN", "B-" + id,
                    null, null, BigDecimal.ZERO, status);
            ReflectionTestUtils.setField(entity, "id", id);
            return entity;
        }

        private void existing(List<ShipmentEntity> shipments, ShipmentLineEntity... lines) {
            when(shipmentRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(shipments);
            when(shipmentLineRepository.findByShipmentIdIn(any())).thenReturn(List.of(lines));
        }

        private void assertRejected(CreateShipmentRequest request, HttpStatus status, String code) {
            assertThatThrownBy(() -> fulfillmentService.createShipment(request, SUPPLIER_ID))
                    .as(code).isInstanceOfSatisfying(CommerceException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(status);
                        assertThat(ex.getCode()).isEqualTo(code);
                    });
        }

        @Test
        @DisplayName("The seller chooses which items and how many units travel in a shipment")
        void shipmentCarriesTheChosenItemsAndQuantities() {
            ShipmentResponse response = fulfillmentService.createShipment(request(List.of(
                    new CreateShipmentRequest.Line(ORDER_ITEM_ID, 1),
                    new CreateShipmentRequest.Line(SECOND_ITEM_ID, 3))), SUPPLIER_ID);

            assertThat(response.lines()).extracting(ShipmentResponse.Line::orderItemId, ShipmentResponse.Line::quantity,
                            ShipmentResponse.Line::productNameSnapshot)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(ORDER_ITEM_ID, 1, "Hộp Trăng Vàng Hoàng Kim"),
                            org.assertj.core.groups.Tuple.tuple(SECOND_ITEM_ID, 3, "Hộp Nguyệt Quế"));
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<List<ShipmentLineEntity>> saved = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(shipmentLineRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).extracting(ShipmentLineEntity::getShipmentId, ShipmentLineEntity::getOrderItemId,
                            ShipmentLineEntity::getQuantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(NEW_SHIPMENT_ID, ORDER_ITEM_ID, 1),
                            org.assertj.core.groups.Tuple.tuple(NEW_SHIPMENT_ID, SECOND_ITEM_ID, 3));
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.PREPARING.name());
        }

        @Test
        @DisplayName("Without lines a shipment takes every unit not yet in a live shipment (backward compatible)")
        void shipmentWithoutLinesTakesTheWholeRemainingOrder() {
            existing(List.of(shipment, batch(302L, ShipmentStatus.CANCELLED)),
                    new ShipmentLineEntity(SHIPMENT_ID, ORDER_ITEM_ID, 1),
                    new ShipmentLineEntity(302L, SECOND_ITEM_ID, 3));

            ShipmentResponse response = fulfillmentService.createShipment(request(null), SUPPLIER_ID);

            // The planned batch holds 1 of the 2 units; the cancelled batch gives its 3 units back.
            assertThat(response.lines()).extracting(ShipmentResponse.Line::orderItemId, ShipmentResponse.Line::quantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(ORDER_ITEM_ID, 1),
                            org.assertj.core.groups.Tuple.tuple(SECOND_ITEM_ID, 3));
        }

        @Test
        @DisplayName("Invalid lines are rejected before anything is saved")
        void invalidLinesAreRejected() {
            assertRejected(request(List.of(new CreateShipmentRequest.Line(ORDER_ITEM_ID, 0))),
                    HttpStatus.BAD_REQUEST, "SHIPMENT_LINE_INVALID_QUANTITY");
            assertRejected(request(List.of(new CreateShipmentRequest.Line(999L, 1))),
                    HttpStatus.BAD_REQUEST, "SHIPMENT_LINE_ITEM_NOT_IN_ORDER");
            assertRejected(request(List.of(new CreateShipmentRequest.Line(ORDER_ITEM_ID, 1),
                    new CreateShipmentRequest.Line(ORDER_ITEM_ID, 1))), HttpStatus.BAD_REQUEST, "SHIPMENT_LINE_DUPLICATE_ITEM");
            assertRejected(request(List.of(new CreateShipmentRequest.Line(ORDER_ITEM_ID, 3))),
                    HttpStatus.CONFLICT, "SHIPMENT_QUANTITY_EXCEEDS_REMAINING");

            verify(shipmentRepository, org.mockito.Mockito.never()).save(any());
            verify(shipmentLineRepository, org.mockito.Mockito.never()).saveAll(any());
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.READY_FOR_FULFILLMENT.name());
        }

        @Test
        @DisplayName("Units already in live shipments cannot be shipped again")
        void allocatedUnitsCannotBeShippedTwice() {
            existing(List.of(shipment), new ShipmentLineEntity(SHIPMENT_ID, ORDER_ITEM_ID, 2),
                    new ShipmentLineEntity(SHIPMENT_ID, SECOND_ITEM_ID, 3));

            assertRejected(request(List.of(new CreateShipmentRequest.Line(ORDER_ITEM_ID, 1))),
                    HttpStatus.CONFLICT, "SHIPMENT_QUANTITY_EXCEEDS_REMAINING");
            assertRejected(request(null), HttpStatus.CONFLICT, "NOTHING_LEFT_TO_SHIP");
        }

        @Test
        @DisplayName("The seller order is DELIVERED only once every ordered unit is in a delivered shipment")
        void orderIsDeliveredOnlyWhenEveryOrderedUnitIsDelivered() {
            sellerOrder.setStatus(OrderStatus.SHIPPING.name());
            shipment.setStatus(ShipmentStatus.SHIPPED);
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            existing(List.of(shipment), new ShipmentLineEntity(SHIPMENT_ID, ORDER_ITEM_ID, 2),
                    new ShipmentLineEntity(SHIPMENT_ID, SECOND_ITEM_ID, 1));

            fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false);

            // Every shipment is delivered, but 2 units of the second item were never sent.
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.SHIPPING.name());
            verify(integration, org.mockito.Mockito.never()).syncFulfillment(org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean());

            ShipmentEntity rest = batch(302L, ShipmentStatus.SHIPPED);
            when(shipmentRepository.findSellerOrderIdById(302L)).thenReturn(Optional.of(SELLER_ORDER_ID));
            when(shipmentRepository.findById(302L)).thenReturn(Optional.of(rest));
            existing(List.of(shipment, rest), new ShipmentLineEntity(SHIPMENT_ID, ORDER_ITEM_ID, 2),
                    new ShipmentLineEntity(SHIPMENT_ID, SECOND_ITEM_ID, 1), new ShipmentLineEntity(302L, SECOND_ITEM_ID, 2));

            fulfillmentService.markAsDelivered(302L, SUPPLIER_ID, false);

            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
            verify(integration).syncFulfillment(SELLER_ORDER_ID, 1, true, false);
        }

        @Test
        @DisplayName("Shipments expose their lines and each item shows shipped, delivered and remaining units")
        void itemProgressShowsShippedDeliveredAndRemainingUnits() {
            existing(List.of(batch(301L, ShipmentStatus.DELIVERED), batch(302L, ShipmentStatus.SHIPPED),
                            batch(303L, ShipmentStatus.PLANNED), batch(304L, ShipmentStatus.CANCELLED)),
                    new ShipmentLineEntity(301L, ORDER_ITEM_ID, 1), new ShipmentLineEntity(302L, SECOND_ITEM_ID, 1),
                    new ShipmentLineEntity(303L, SECOND_ITEM_ID, 1), new ShipmentLineEntity(304L, ORDER_ITEM_ID, 1));
            var supplier = new FinanceService.Actor(999, null, SUPPLIER_ID, false);

            List<ShipmentItemProgressResponse> progress = fulfillmentService.getShipmentItemProgress(SELLER_ORDER_ID, supplier);

            assertThat(progress).extracting(ShipmentItemProgressResponse::orderItemId,
                            ShipmentItemProgressResponse::orderedQuantity, ShipmentItemProgressResponse::allocatedQuantity,
                            ShipmentItemProgressResponse::shippedQuantity, ShipmentItemProgressResponse::deliveredQuantity,
                            ShipmentItemProgressResponse::remainingQuantity)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(ORDER_ITEM_ID, 2, 1, 1, 1, 1),
                            org.assertj.core.groups.Tuple.tuple(SECOND_ITEM_ID, 3, 2, 1, 0, 1));
            assertThat(fulfillmentService.getShipmentsBySellerOrder(SELLER_ORDER_ID, supplier))
                    .extracting(s -> s.lines().size()).containsExactly(1, 1, 1, 1);
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));
            assertThatThrownBy(() -> fulfillmentService.getShipmentItemProgress(SELLER_ORDER_ID,
                    new FinanceService.Actor(998, OTHER_CUSTOMER_ID, OTHER_SUPPLIER_ID, false)))
                    .isInstanceOf(ForbiddenException.class);
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
    @DisplayName("Financial case gates (QA B1/M7)")
    class FinancialCaseGateTests {

        private mss.mooncake.commerce.dto.response.FinanceResponse.Case caseWith(String status) {
            return new mss.mooncake.commerce.dto.response.FinanceResponse.Case(1L, status, "reason", null, null, null);
        }

        private CreateShipmentRequest request() {
            return new CreateShipmentRequest(SELLER_ORDER_ID, "STANDARD", "GHTK", "VN123456789",
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(3), BigDecimal.ZERO);
        }

        @Test
        @DisplayName("A fully refunded order can no longer be shipped")
        void fullyRefundedOrderCannotBeShipped() {
            when(finance.refunded(SELLER_ORDER_ID)).thenReturn(300000L);
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));

            assertThatThrownBy(() -> fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Insufficient payment");
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PLANNED);
        }

        @Test
        @DisplayName("A refund awaiting transfer proof blocks shipping")
        void pendingRefundBlocksShipping() {
            when(finance.refunds(SELLER_ORDER_ID)).thenReturn(List.of(caseWith("MANUAL_REVIEW")));
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));

            assertThatThrownBy(() -> fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID))
                    .isInstanceOf(ConflictException.class);
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PLANNED);
        }

        @Test
        @DisplayName("An unresolved dispute blocks preparation and shipping in every stage")
        void unresolvedDisputeBlocksPreparationAndShipping() {
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            for (String status : List.of("OPEN", "ADMIN_REVIEW", "REFUND_PENDING")) {
                when(finance.disputes(SELLER_ORDER_ID)).thenReturn(List.of(caseWith(status)));
                assertThatThrownBy(() -> fulfillmentService.createShipment(request(), SUPPLIER_ID))
                        .as(status).isInstanceOf(ConflictException.class);
                assertThatThrownBy(() -> fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID))
                        .as(status).isInstanceOf(ConflictException.class);
            }
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.READY_FOR_FULFILLMENT.name());
        }

        @Test
        @DisplayName("A resolved dispute no longer blocks shipping")
        void resolvedDisputeDoesNotBlockShipping() {
            when(finance.disputes(SELLER_ORDER_ID)).thenReturn(List.of(caseWith("RESOLVED")));
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            assertThat(fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID).status())
                    .isEqualTo(ShipmentStatus.SHIPPED);
        }

        @Test
        @DisplayName("The buyer cannot accept (nghiệm thu) while a dispute is unresolved")
        void unresolvedDisputeBlocksBuyerAcceptance() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));
            for (String status : List.of("OPEN", "ADMIN_REVIEW", "REFUND_PENDING")) {
                when(finance.disputes(SELLER_ORDER_ID)).thenReturn(List.of(caseWith(status)));
                assertThatThrownBy(() -> fulfillmentService.acceptSellerOrder(SELLER_ORDER_ID, CUSTOMER_ID))
                        .as(status).isInstanceOf(ConflictException.class);
            }
            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
            org.mockito.Mockito.verifyNoInteractions(integration);
        }
    }

    @Nested
    @DisplayName("Planned shipment cancellation and schedule validation (QA M5)")
    class ShipmentCancellationTests {

        @Test
        @DisplayName("Supplier cancels its planned shipment")
        void supplierCancelsPlannedShipment() {
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            assertThat(fulfillmentService.cancelShipment(SHIPMENT_ID, SUPPLIER_ID).status())
                    .isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
        }

        @Test
        @DisplayName("Only a planned shipment can be cancelled, and only by its supplier")
        void onlyOwnPlannedShipmentCanBeCancelled() {
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            assertThatThrownBy(() -> fulfillmentService.cancelShipment(SHIPMENT_ID, OTHER_SUPPLIER_ID))
                    .isInstanceOf(ForbiddenException.class);
            for (ShipmentStatus status : List.of(ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED)) {
                shipment.setStatus(status);
                assertThatThrownBy(() -> fulfillmentService.cancelShipment(SHIPMENT_ID, SUPPLIER_ID))
                        .as(status.name()).isInstanceOf(ConflictException.class);
            }
        }

        @Test
        @DisplayName("A cancelled shipment can no longer be edited")
        void cancelledShipmentCannotBeUpdated() {
            shipment.setStatus(ShipmentStatus.CANCELLED);
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            assertThatThrownBy(() -> fulfillmentService.updateShipment(SHIPMENT_ID,
                    new UpdateShipmentRequest(null, null, "NEW", null), SUPPLIER_ID))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("A cancelled batch does not keep the order from becoming DELIVERED")
        void cancelledShipmentDoesNotBlockDelivery() {
            sellerOrder.setStatus(OrderStatus.SHIPPING.name());
            shipment.setStatus(ShipmentStatus.SHIPPED);
            ShipmentEntity cancelled = new ShipmentEntity(SELLER_ORDER_ID, "STANDARD", "GHN", null,
                    null, null, BigDecimal.ZERO, ShipmentStatus.CANCELLED);
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(shipmentRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(List.of(shipment, cancelled));

            fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false);

            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
        }

        @Test
        @DisplayName("Expected delivery cannot be before expected ship time")
        void deliveryBeforeShipTimeIsRejected() {
            LocalDateTime ship = LocalDateTime.now().plusDays(3);
            assertThatThrownBy(() -> fulfillmentService.createShipment(new CreateShipmentRequest(SELLER_ORDER_ID,
                    "STANDARD", "GHTK", null, ship, ship.minusDays(1), BigDecimal.ZERO), SUPPLIER_ID))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("expectedDeliveryAt");

            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            assertThatThrownBy(() -> fulfillmentService.updateShipment(SHIPMENT_ID, new UpdateShipmentRequest(
                    null, null, null, shipment.getExpectedShipAt().minusHours(1)), SUPPLIER_ID))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("expectedDeliveryAt");
        }
    }

    @Nested
    @DisplayName("Auto-acceptance once the dispute window has passed")
    class AutoAcceptanceTests {
        private final java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(7));

        @BeforeEach
        void deliveredButNotAccepted() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(finance.context(SELLER_ORDER_ID)).thenReturn(new mss.mooncake.commerce.repository.FinanceRepository.Context(
                    SELLER_ORDER_ID, CUSTOMER_ID, SUPPLIER_ID, 300000, null, null, true, 1, 1, true, false));
        }

        @Test
        @DisplayName("A delivery nobody disputed is accepted by the system after seven days")
        void undisputedDeliveryIsAcceptedAfterTheWindow() {
            when(finance.deliveredAt(SELLER_ORDER_ID)).thenReturn(java.time.Instant.now().minus(java.time.Duration.ofDays(8)));

            assertThat(fulfillmentService.autoAcceptDelivered(SELLER_ORDER_ID, cutoff)).isTrue();

            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.ACCEPTED.name());
            assertThat(sellerOrder.getAcceptedAt()).isNotNull();
            verify(integration).syncFulfillment(SELLER_ORDER_ID, 2, true, true);
            verify(integration).syncCustomerOrder(SELLER_ORDER_ID);
            verify(finance).event(org.mockito.ArgumentMatchers.eq(SELLER_ORDER_ID.longValue()),
                    org.mockito.ArgumentMatchers.eq("ORDER_AUTO_ACCEPTED"),
                    org.mockito.ArgumentMatchers.eq(SELLER_ORDER_ID.longValue()),
                    org.mockito.ArgumentMatchers.eq("SYSTEM"), any());
        }

        @Test
        @DisplayName("A disputed or recent delivery is left to the buyer")
        void disputedOrRecentDeliveryIsNotAutoAccepted() {
            var deliveredAt = java.time.Instant.now().minus(java.time.Duration.ofDays(8));
            when(finance.deliveredAt(SELLER_ORDER_ID)).thenReturn(deliveredAt);
            when(finance.disputes(SELLER_ORDER_ID)).thenReturn(List.of(new mss.mooncake.commerce.dto.response.FinanceResponse.Case(
                    1L, "RESOLVED", "Damaged", null, null, null, null, deliveredAt.plusSeconds(60), null, null,
                    "REJECT", null)));
            assertThat(fulfillmentService.autoAcceptDelivered(SELLER_ORDER_ID, cutoff)).isFalse();

            org.mockito.Mockito.lenient().when(finance.disputes(SELLER_ORDER_ID)).thenReturn(List.of());
            when(finance.deliveredAt(SELLER_ORDER_ID)).thenReturn(java.time.Instant.now().minus(java.time.Duration.ofDays(6)));
            assertThat(fulfillmentService.autoAcceptDelivered(SELLER_ORDER_ID, cutoff)).isFalse();

            assertThat(sellerOrder.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
            org.mockito.Mockito.verifyNoInteractions(integration);
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
            assertThat(response.productNameSnapshot()).isEqualTo("Hộp Trăng Vàng Hoàng Kim");
            assertThat(response.variantNameSnapshot()).isEqualTo("Hộp 4 bánh - Ít ngọt");
            assertThat(response.displayName()).isEqualTo("Khách hàng #701");
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

    @Nested
    @DisplayName("In-app notifications")
    class NotificationTests {

        private void shipmentFound() {
            when(shipmentRepository.findById(SHIPMENT_ID)).thenReturn(Optional.of(shipment));
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(shipmentRepository.save(any(ShipmentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("Shipping a batch notifies the buyer with carrier and tracking code")
        void shippingNotifiesTheBuyer() {
            shipmentFound();

            fulfillmentService.markAsShipped(SHIPMENT_ID, SUPPLIER_ID);

            verify(notifier).shipmentShipped(SELLER_ORDER_ID, SHIPMENT_ID, "GHTK", "VN123456789");
        }

        @Test
        @DisplayName("The delivery that completes the order also asks the buyer to accept it")
        void lastDeliveryAsksTheBuyerToAccept() {
            shipment.setStatus(ShipmentStatus.SHIPPED);
            shipmentFound();

            fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false);

            verify(notifier).shipmentDelivered(SELLER_ORDER_ID, SHIPMENT_ID, true);
        }

        @Test
        @DisplayName("A partial delivery only reports the delivered batch")
        void partialDeliveryReportsOnlyTheBatch() {
            sellerOrder.setStatus(OrderStatus.SHIPPING.name());
            shipment.setStatus(ShipmentStatus.SHIPPED);
            shipmentFound();
            when(shipmentRepository.findBySellerOrderId(SELLER_ORDER_ID)).thenReturn(List.of(shipment,
                    new ShipmentEntity(SELLER_ORDER_ID, "STANDARD", "GHN", "GHN-2", null, null, BigDecimal.ZERO,
                            ShipmentStatus.SHIPPED)));

            fulfillmentService.markAsDelivered(SHIPMENT_ID, SUPPLIER_ID, false);

            verify(notifier).shipmentDelivered(SELLER_ORDER_ID, SHIPMENT_ID, false);
        }

        @Test
        @DisplayName("Buyer acceptance notifies the supplier")
        void buyerAcceptanceNotifiesTheSupplier() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(customerOrderRepository.findById(CUSTOMER_ORDER_ID)).thenReturn(Optional.of(customerOrder));
            when(sellerOrderRepository.saveAndFlush(any(SellerOrderEntity.class))).thenAnswer(i -> i.getArgument(0));

            fulfillmentService.acceptSellerOrder(SELLER_ORDER_ID, CUSTOMER_ID);

            verify(notifier).orderAccepted(SELLER_ORDER_ID, false);
        }

        @Test
        @DisplayName("System acceptance after the dispute window notifies the supplier")
        void autoAcceptanceNotifiesTheSupplier() {
            sellerOrder.setStatus(OrderStatus.DELIVERED.name());
            when(sellerOrderRepository.findById(SELLER_ORDER_ID)).thenReturn(Optional.of(sellerOrder));
            when(finance.context(SELLER_ORDER_ID)).thenReturn(new mss.mooncake.commerce.repository.FinanceRepository.Context(
                    SELLER_ORDER_ID, CUSTOMER_ID, SUPPLIER_ID, 300000, null, null, true, 1, 1, true, false));
            when(finance.deliveredAt(SELLER_ORDER_ID)).thenReturn(java.time.Instant.now().minus(java.time.Duration.ofDays(8)));

            fulfillmentService.autoAcceptDelivered(SELLER_ORDER_ID, java.time.Instant.now().minus(java.time.Duration.ofDays(7)));

            verify(notifier).orderAccepted(SELLER_ORDER_ID, true);
        }

        @Test
        @DisplayName("An Identity outage never fails shipping; the buyer notice was attempted")
        void identityOutageNeverFailsShipping() {
            var identity = org.mockito.Mockito.mock(IdentityServiceClient.class);
            org.mockito.Mockito.doThrow(new IllegalStateException("identity down"))
                    .when(identity).createNotification(any());
            when(finance.parentOrderId(SELLER_ORDER_ID)).thenReturn(CUSTOMER_ORDER_ID);
            var service = new FulfillmentApplicationServiceImpl(shipmentRepository, reviewRepository,
                    sellerOrderRepository, customerOrderRepository, orderItemRepository, shipmentLineRepository,
                    finance, integration, new CommerceNotifier(identity, finance));
            shipmentFound();

            assertThat(service.markAsShipped(SHIPMENT_ID, SUPPLIER_ID).status()).isEqualTo(ShipmentStatus.SHIPPED);

            var sent = org.mockito.ArgumentCaptor.forClass(CreateNotificationRequest.class);
            verify(identity).createNotification(sent.capture());
            assertThat(sent.getValue().customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(sent.getValue().type()).isEqualTo("SHIPMENT_SHIPPED");
            assertThat(sent.getValue().dedupKey()).isEqualTo("SHIPMENT_SHIPPED:" + SHIPMENT_ID);
            assertThat(sent.getValue().target()).isEqualTo(java.util.Map.of("kind", "ORDER", "id",
                    String.valueOf(CUSTOMER_ORDER_ID)));
        }
    }
}
