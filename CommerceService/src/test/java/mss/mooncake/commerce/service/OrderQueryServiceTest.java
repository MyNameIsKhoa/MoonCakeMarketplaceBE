package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import feign.FeignException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotResponse;
import mss.mooncake.commerce.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import mss.mooncake.commerce.entity.CancellationEntity;
import mss.mooncake.commerce.entity.DeliveryAddressEntity;
import mss.mooncake.commerce.entity.OrderItemEntity;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import mss.mooncake.commerce.repository.CustomerOrderRepository;
import mss.mooncake.commerce.repository.CancellationRepository;
import mss.mooncake.commerce.repository.DeliveryAddressRepository;
import mss.mooncake.commerce.repository.OrderItemRepository;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import mss.mooncake.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderQueryServiceTest {
    @Autowired private OrderQueryService queries;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private DeliveryAddressRepository addresses;
    @Autowired private SellerOrderRepository sellers;
    @Autowired private OrderItemRepository items;
    @Autowired private CancellationRepository cancellations;
    @Autowired private EntityManager entityManager;
    @Autowired private WebApplicationContext context;
    @MockitoBean private IdentityServiceClient identity;
    @MockitoBean private CatalogServiceClient catalog;

    @Test
    void summaryAggregatesOnlyOwnedPageAndPreservesHttpContract() throws Exception {
        CustomerOrderEntity order = order("summary", 500L);
        SellerOrderEntity first = seller(order, "first", "SHIPPING");
        SellerOrderEntity second = seller(order, "second", "AWAITING_PAYMENT");
        seller(order, "third", "SHIPPING");
        item(first, null, null, 5);
        item(first, null, null, 3);
        item(second, null, null, 2);
        cancellation(first, "REJECTED");
        cancellation(first, "REQUESTED");
        CustomerOrderEntity empty = order("empty", 500L);
        CustomerOrderEntity other = order("other", 501L);
        SellerOrderEntity otherSeller = seller(other, "other", "CANCELLED");
        item(otherSeller, null, null, 1);
        cancellation(otherSeller, "APPROVED");

        var page = queries.listCustomerOrders(500L, PageRequest.of(0, 1, Sort.by("id")));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
        var summary = page.getContent().getFirst();
        assertThat(summary.orderId()).isEqualTo(order.getId());
        assertThat(summary.sellerOrderCount()).isEqualTo(3);
        assertThat(summary.itemCount()).isEqualTo(3);
        assertThat(summary.sellerOrderStatuses()).containsExactly("SHIPPING", "AWAITING_PAYMENT");
        assertThat(summary.hasCancellation()).isTrue();
        var emptySummary = queries.listCustomerOrders(500L, PageRequest.of(1, 1, Sort.by("id")))
                .getContent().getFirst();
        assertThat(emptySummary.orderId()).isEqualTo(empty.getId());
        assertThat(emptySummary.sellerOrderCount()).isZero();
        assertThat(emptySummary.itemCount()).isZero();
        assertThat(emptySummary.sellerOrderStatuses()).isEmpty();
        assertThat(emptySummary.hasCancellation()).isFalse();
        verifyNoInteractions(catalog, identity);

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        var buyer = new UsernamePasswordAuthenticationToken(
                new CommercePrincipal(50L, 500L, "buyer@example.com", authorities), null, authorities);
        mvc.perform(get("/api/orders").with(authentication(buyer)).param("size", "1").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].orderId").value(order.getId()))
                .andExpect(jsonPath("$.content[0].orderNo").value(order.getOrderNo()))
                .andExpect(jsonPath("$.content[0].grandTotal").value(100))
                .andExpect(jsonPath("$.content[0].sellerOrderCount").value(3))
                .andExpect(jsonPath("$.content[0].itemCount").value(3))
                .andExpect(jsonPath("$.content[0].sellerOrderStatuses[1]").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.content[0].hasCancellation").value(true));
        mvc.perform(get("/api/orders/{id}", other.getId()).with(authentication(buyer)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalog, identity);
    }

    @Test
    void cancellationHistoryIncludesRejectedCasesAndDoesNotLeakBetweenOrders() {
        CustomerOrderEntity rejected = order("rejected", 510L);
        cancellation(seller(rejected, "rejected", "SHIPPING"), "REJECTED");
        CustomerOrderEntity clean = order("clean", 510L);
        seller(clean, "clean", "SHIPPING");
        var summaries = queries.listCustomerOrders(510L, PageRequest.of(0, 20, Sort.by("id"))).getContent();
        assertThat(summaries).extracting(s -> s.hasCancellation()).containsExactly(true, false);
    }

    @Test
    void detailUsesOnlyMatchingCatalogImagesAndNeverChangesStoredSnapshots() {
        CustomerOrderEntity order = order("images", 520L);
        SellerOrderEntity first = seller(order, "images-a", "AWAITING_DEPOSIT");
        SellerOrderEntity second = seller(order, "images-b", "AWAITING_DEPOSIT");
        OrderItemEntity missing = item(first, 101L, null, 2);
        item(first, 102L, "stored.png", 1);
        item(first, 103L, null, 1);
        item(first, 104L, null, 1);
        item(first, null, null, 1);
        item(second, 101L, null, 1);
        when(catalog.getVariantSnapshots(any())).thenAnswer(invocation -> {
            VariantSnapshotBatchRequest request = invocation.getArgument(0);
            assertThat(request.variantIds()).containsExactlyInAnyOrder(101L, 103L, 104L);
            return List.of(snapshot(101L, 12L, "live.png"), snapshot(103L, 999L, "wrong-store.png"),
                    snapshot(999L, 12L, "wrong-variant.png"));
        });

        var detail = queries.getCustomerOrder(520L, order.getId());
        var lines = detail.sellerOrders().stream().flatMap(s -> s.items().stream()).toList();
        assertThat(lines).extracting(i -> i.imageUrl()).containsExactlyInAnyOrder(
                "live.png", "stored.png", null, null, null, "live.png");
        assertThat(lines).filteredOn(i -> i.variantIdRef() != null && i.variantIdRef().equals(101L))
                .allSatisfy(i -> assertThat(i.imageUrlSnapshot()).isNull());
        assertThat(detail.grandTotal()).isEqualByComparingTo("100");
        org.mockito.Mockito.verify(catalog).getVariantSnapshots(any());
        entityManager.flush();
        entityManager.clear();
        assertThat(items.findById(missing.getId()).orElseThrow().getImageUrlSnapshot()).isNull();
    }

    @Test
    void catalogOutageDoesNotPreventReadingOrderDetail() {
        CustomerOrderEntity order = order("outage", 530L);
        SellerOrderEntity seller = seller(order, "outage", "AWAITING_DEPOSIT");
        item(seller, 101L, null, 1);
        item(seller, 102L, "stored.png", 1);
        when(catalog.getVariantSnapshots(any())).thenThrow(org.mockito.Mockito.mock(FeignException.class));

        var detail = queries.getCustomerOrder(530L, order.getId());
        assertThat(detail.sellerOrders().getFirst().items()).extracting(i -> i.imageUrl())
                .containsExactlyInAnyOrder(null, "stored.png");
        assertThat(detail.grandTotal()).isEqualByComparingTo("100");
    }

    @Test
    void buyerReadsStoredSnapshotButOtherBuyerCannotSeeOrder() {
        CustomerOrderEntity order = new CustomerOrderEntity();
        set(order, "orderNo", "MSS-test-200");
        set(order, "customerIdRef", 200L);
        set(order, "sourceType", "RETAIL");
        set(order, "subtotal", new BigDecimal("100.00"));
        set(order, "discountTotal", BigDecimal.ZERO);
        set(order, "shippingTotal", BigDecimal.ZERO);
        set(order, "grandTotal", new BigDecimal("100.00"));
        set(order, "status", "AWAITING_PAYMENT");
        set(order, "placedAt", LocalDateTime.now());
        order = orders.saveAndFlush(order);

        DeliveryAddressEntity address = new DeliveryAddressEntity();
        set(address, "orderId", order.getId());
        set(address, "recipientName", "Buyer");
        set(address, "recipientPhone", "0900000000");
        set(address, "addressLine", "Old address");
        set(address, "city", "Hanoi");
        addresses.save(address);

        SellerOrderEntity seller = new SellerOrderEntity();
        set(seller, "orderId", order.getId());
        set(seller, "sellerOrderNo", "MSS-test-200-1");
        set(seller, "storeIdRef", 12L);
        set(seller, "supplierIdRef", 22L);
        set(seller, "storeNameSnapshot", "Old store name");
        set(seller, "supplierNameSnapshot", "Old supplier name");
        set(seller, "subtotal", new BigDecimal("100.00"));
        set(seller, "shippingFee", BigDecimal.ZERO);
        set(seller, "totalAmount", new BigDecimal("100.00"));
        set(seller, "status", "AWAITING_PAYMENT");
        seller = sellers.saveAndFlush(seller);

        OrderItemEntity item = new OrderItemEntity();
        set(item, "sellerOrderId", seller.getId());
        set(item, "productNameSnapshot", "Old mooncake name");
        set(item, "quantity", 1);
        set(item, "unitPrice", new BigDecimal("100.00"));
        set(item, "discountAmount", BigDecimal.ZERO);
        set(item, "subtotal", new BigDecimal("100.00"));
        items.save(item);

        var detail = queries.getCustomerOrder(200L, order.getId());
        assertThat(detail.deliveryAddress().addressLine()).isEqualTo("Old address");
        assertThat(detail.sellerOrders()).hasSize(1);
        assertThat(detail.sellerOrders().getFirst().items().getFirst().productNameSnapshot())
                .isEqualTo("Old mooncake name");
        assertThat(queries.listCustomerOrders(200L, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        assertThat(queries.listCustomerOrders(201L, PageRequest.of(0, 20)).getTotalElements()).isZero();
        Long orderId = order.getId();
        assertThatThrownBy(() -> queries.getCustomerOrder(201L, orderId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        when(identity.getSupplierSummaryByUser(30L))
                .thenReturn(new SupplierSummaryResponse(22L, 30L, "Supplier", null, "VERIFIED"));
        when(identity.getSupplierSummaryByUser(31L))
                .thenReturn(new SupplierSummaryResponse(23L, 31L, "Other", null, "VERIFIED"));
        assertThat(queries.listSupplierOrders(30L, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
        Long sellerId = seller.getId();
        assertThat(queries.getSellerOrder(30L, null, sellerId, false).items().getFirst().productNameSnapshot())
                .isEqualTo("Old mooncake name");
        assertThatThrownBy(() -> queries.getSellerOrder(31L, null, sellerId, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void supplierSeesDeliveryAddressSnapshotAndDiscountOfItsSellerOrder() {
        CustomerOrderEntity order = new CustomerOrderEntity();
        set(order, "orderNo", "MSS-test-300");
        set(order, "customerIdRef", 300L);
        set(order, "sourceType", "RETAIL");
        set(order, "subtotal", new BigDecimal("200.00"));
        set(order, "discountTotal", new BigDecimal("30.00"));
        set(order, "shippingTotal", BigDecimal.ZERO);
        set(order, "grandTotal", new BigDecimal("170.00"));
        set(order, "status", "READY_FOR_FULFILLMENT");
        set(order, "placedAt", LocalDateTime.now());
        order = orders.saveAndFlush(order);

        DeliveryAddressEntity address = new DeliveryAddressEntity();
        set(address, "orderId", order.getId());
        set(address, "sourceAddressIdRef", 77L);
        set(address, "recipientName", "Nguyễn Văn A");
        set(address, "recipientPhone", "0900000001");
        set(address, "addressLine", "12 Lê Lợi");
        set(address, "ward", "Bến Nghé");
        set(address, "district", "Quận 1");
        set(address, "city", "TP Hồ Chí Minh");
        addresses.save(address);

        SellerOrderEntity seller = new SellerOrderEntity();
        set(seller, "orderId", order.getId());
        set(seller, "sellerOrderNo", "MSS-test-300-1");
        set(seller, "storeIdRef", 32L);
        set(seller, "supplierIdRef", 42L);
        set(seller, "storeNameSnapshot", "Store");
        set(seller, "supplierNameSnapshot", "Supplier");
        set(seller, "subtotal", new BigDecimal("200.00"));
        set(seller, "shippingFee", BigDecimal.ZERO);
        set(seller, "totalAmount", new BigDecimal("170.00"));
        set(seller, "status", "READY_FOR_FULFILLMENT");
        seller = sellers.saveAndFlush(seller);
        for (String discount : new String[]{"10.00", "20.00"}) {
            OrderItemEntity item = new OrderItemEntity();
            set(item, "sellerOrderId", seller.getId());
            set(item, "productNameSnapshot", "Mooncake");
            set(item, "quantity", 1);
            set(item, "unitPrice", new BigDecimal("100.00"));
            set(item, "discountAmount", new BigDecimal(discount));
            set(item, "subtotal", new BigDecimal("100.00"));
            items.save(item);
        }
        when(identity.getSupplierSummaryByUser(50L))
                .thenReturn(new SupplierSummaryResponse(42L, 50L, "Supplier", null, "VERIFIED"));

        var view = queries.getSellerOrder(50L, null, seller.getId(), false);

        assertThat(view.discountTotal()).isEqualByComparingTo("30.00");
        var delivery = view.deliveryAddress();
        assertThat(delivery.recipientName()).isEqualTo("Nguyễn Văn A");
        assertThat(delivery.recipientPhone()).isEqualTo("0900000001");
        assertThat(delivery.addressLine()).isEqualTo("12 Lê Lợi");
        assertThat(delivery.ward()).isEqualTo("Bến Nghé");
        assertThat(delivery.district()).isEqualTo("Quận 1");
        assertThat(delivery.city()).isEqualTo("TP Hồ Chí Minh");
        assertThat(queries.getCustomerOrder(300L, order.getId()).sellerOrders().getFirst().deliveryAddress()
                .addressLine()).isEqualTo("12 Lê Lợi");
    }

    @Test
    void adminReadsAnySellerOrderWithoutASupplierProfile() {
        CustomerOrderEntity order = new CustomerOrderEntity();
        set(order, "orderNo", "MSS-test-400");
        set(order, "customerIdRef", 400L);
        set(order, "sourceType", "RETAIL");
        set(order, "subtotal", new BigDecimal("100.00"));
        set(order, "discountTotal", BigDecimal.ZERO);
        set(order, "shippingTotal", BigDecimal.ZERO);
        set(order, "grandTotal", new BigDecimal("100.00"));
        set(order, "status", "AWAITING_PAYMENT");
        set(order, "placedAt", LocalDateTime.now());
        order = orders.saveAndFlush(order);
        SellerOrderEntity seller = new SellerOrderEntity();
        set(seller, "orderId", order.getId());
        set(seller, "sellerOrderNo", "MSS-test-400-1");
        set(seller, "storeIdRef", 52L);
        set(seller, "supplierIdRef", 62L);
        set(seller, "storeNameSnapshot", "Store");
        set(seller, "supplierNameSnapshot", "Supplier");
        set(seller, "subtotal", new BigDecimal("100.00"));
        set(seller, "shippingFee", BigDecimal.ZERO);
        set(seller, "totalAmount", new BigDecimal("100.00"));
        set(seller, "status", "AWAITING_PAYMENT");
        Long sellerId = sellers.saveAndFlush(seller).getId();

        assertThat(queries.getSellerOrder(900L, null, sellerId, true).sellerOrderId()).isEqualTo(sellerId);
        org.mockito.Mockito.verifyNoInteractions(identity);
        assertThatThrownBy(() -> queries.getSellerOrder(900L, null, sellerId, false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    private CustomerOrderEntity order(String suffix, Long customerId) {
        CustomerOrderEntity order = new CustomerOrderEntity();
        order.setOrderNo("query-" + suffix);
        order.setCustomerIdRef(customerId);
        order.setSourceType("RFQ");
        order.setStatus("AWAITING_DEPOSIT");
        order.setPlacedAt(LocalDateTime.now());
        order.setSubtotal(new BigDecimal("100"));
        order.setDiscountTotal(BigDecimal.ZERO);
        order.setShippingTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("100"));
        order = orders.saveAndFlush(order);
        DeliveryAddressEntity address = new DeliveryAddressEntity();
        set(address, "orderId", order.getId());
        set(address, "recipientName", "Buyer");
        set(address, "recipientPhone", "0900000000");
        set(address, "addressLine", "Snapshot address");
        set(address, "city", "Hanoi");
        addresses.save(address);
        return order;
    }

    private SellerOrderEntity seller(CustomerOrderEntity order, String suffix, String status) {
        SellerOrderEntity seller = new SellerOrderEntity();
        seller.setOrderId(order.getId());
        seller.setSellerOrderNo("query-seller-" + suffix);
        seller.setStoreIdRef(12L);
        seller.setSupplierIdRef(22L);
        seller.setStoreNameSnapshot("Store");
        seller.setSupplierNameSnapshot("Supplier");
        seller.setSubtotal(new BigDecimal("100"));
        seller.setShippingFee(BigDecimal.ZERO);
        seller.setTotalAmount(new BigDecimal("100"));
        seller.setStatus(status);
        return sellers.saveAndFlush(seller);
    }

    private OrderItemEntity item(SellerOrderEntity seller, Long variantId, String imageUrl, int quantity) {
        OrderItemEntity item = new OrderItemEntity();
        item.setSellerOrderId(seller.getId());
        item.setVariantIdRef(variantId);
        item.setImageUrlSnapshot(imageUrl);
        item.setProductNameSnapshot("Mooncake");
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal("100"));
        item.setDiscountAmount(BigDecimal.ZERO);
        item.setSubtotal(new BigDecimal("100"));
        return items.saveAndFlush(item);
    }

    private void cancellation(SellerOrderEntity seller, String status) {
        CancellationEntity cancellation = new CancellationEntity();
        set(cancellation, "sellerOrderId", seller.getId());
        set(cancellation, "actorType", "CUSTOMER");
        set(cancellation, "actorIdRef", 500L);
        set(cancellation, "reason", "Cancellation history");
        set(cancellation, "status", status);
        cancellations.saveAndFlush(cancellation);
    }

    private VariantSnapshotResponse snapshot(Long variantId, Long storeId, String imageUrl) {
        return new VariantSnapshotResponse(variantId, 1L, storeId, 22L, "P", "Changed product", "Variant",
                "SKU", imageUrl, new BigDecimal("999"), 1, "ACTIVE", "ACTIVE", "ACTIVE", 1);
    }

    private void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
