package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import mss.mooncake.commerce.entity.DeliveryAddressEntity;
import mss.mooncake.commerce.entity.OrderItemEntity;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import mss.mooncake.commerce.repository.CustomerOrderRepository;
import mss.mooncake.commerce.repository.DeliveryAddressRepository;
import mss.mooncake.commerce.repository.OrderItemRepository;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderQueryServiceTest {
    @Autowired private OrderQueryService queries;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private DeliveryAddressRepository addresses;
    @Autowired private SellerOrderRepository sellers;
    @Autowired private OrderItemRepository items;
    @MockitoBean private IdentityServiceClient identity;

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
        assertThat(queries.getSellerOrder(30L, null, sellerId).items().getFirst().productNameSnapshot())
                .isEqualTo("Old mooncake name");
        assertThatThrownBy(() -> queries.getSellerOrder(31L, null, sellerId))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    private void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
