package mss.mooncake.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.catalog.VariantSnapshotBatchRequest;
import mss.mooncake.commerce.entity.CustomerOrderEntity;
import mss.mooncake.commerce.entity.DeliveryAddressEntity;
import mss.mooncake.commerce.entity.OrderItemEntity;
import mss.mooncake.commerce.entity.SellerOrderEntity;
import mss.mooncake.commerce.repository.CancellationRepository;
import mss.mooncake.commerce.repository.CustomerOrderRepository;
import mss.mooncake.commerce.repository.DeliveryAddressRepository;
import mss.mooncake.commerce.repository.OrderItemRepository;
import mss.mooncake.commerce.repository.SellerOrderRepository;
import mss.mooncake.commerce.service.impl.OrderQueryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceBatchTest {
    @Mock private CustomerOrderRepository orders;
    @Mock private DeliveryAddressRepository addresses;
    @Mock private SellerOrderRepository sellers;
    @Mock private OrderItemRepository items;
    @Mock private CancellationRepository cancellations;
    @Mock private IdentityServiceClient identity;
    @Mock private CatalogServiceClient catalog;
    @InjectMocks private OrderQueryServiceImpl queries;

    @Test
    void listLoadsEachAssociationOnceForTheWholePageWithoutRemoteCalls() {
        var pageable = PageRequest.of(0, 20);
        CustomerOrderEntity first = new CustomerOrderEntity();
        CustomerOrderEntity second = new CustomerOrderEntity();
        ReflectionTestUtils.setField(first, "id", 1L);
        ReflectionTestUtils.setField(second, "id", 2L);
        SellerOrderEntity firstSeller = seller(11L, 1L);
        SellerOrderEntity secondSeller = seller(12L, 2L);
        when(orders.findByCustomerIdRef(100L, pageable)).thenReturn(new PageImpl<>(List.of(first, second)));
        when(sellers.findByOrderIdInOrderByIdAsc(List.of(1L, 2L))).thenReturn(List.of(firstSeller, secondSeller));
        when(items.findBySellerOrderIdIn(List.of(11L, 12L))).thenReturn(List.of());
        when(cancellations.sellerOrderIdsWithCancellation(List.of(11L, 12L))).thenReturn(List.of(12L));

        var page = queries.listCustomerOrders(100L, pageable);

        assertThat(page.getContent()).extracting(s -> s.hasCancellation()).containsExactly(false, true);
        verify(orders).findByCustomerIdRef(100L, pageable);
        verify(sellers).findByOrderIdInOrderByIdAsc(List.of(1L, 2L));
        verify(items).findBySellerOrderIdIn(List.of(11L, 12L));
        verify(cancellations).sellerOrderIdsWithCancellation(List.of(11L, 12L));
        verifyNoMoreInteractions(orders, sellers, items, cancellations);
        verifyNoInteractions(catalog, identity, addresses);
    }

    @Test
    void emptyPageSkipsAssociationsAndRetainsPageMetadata() {
        var pageable = PageRequest.of(2, 20);
        when(orders.findByCustomerIdRef(100L, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 21));
        var page = queries.listCustomerOrders(100L, pageable);
        assertThat(page).isEmpty();
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(21);
        verifyNoInteractions(sellers, items, cancellations, catalog, identity, addresses);
    }

    @Test
    void foreignOrMissingDetailStopsBeforeReadingChildrenOrCallingCatalog() {
        when(orders.findByIdAndCustomerIdRef(1L, 200L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> queries.getCustomerOrder(200L, 1L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        verifyNoInteractions(sellers, items, cancellations, catalog, identity, addresses);
    }

    @Test
    void listStillRejectsPageSizeOverOneHundred() {
        assertThatThrownBy(() -> queries.listCustomerOrders(100L, PageRequest.of(0, 101)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(orders, sellers, items, cancellations, catalog, identity, addresses);
    }

    @Test
    void detailBatchesAtCatalogLimitAndDeduplicatesMissingVariantsAcrossSellers() {
        CustomerOrderEntity order = new CustomerOrderEntity();
        when(orders.findByIdAndCustomerIdRef(1L, 100L)).thenReturn(Optional.of(order));
        when(addresses.findByOrderId(1L)).thenReturn(Optional.of(new DeliveryAddressEntity()));
        when(sellers.findByOrderIdOrderByIdAsc(1L)).thenReturn(List.of(seller(11L, 1L), seller(12L, 1L)));
        var lines = new java.util.ArrayList<>(LongStream.rangeClosed(1, 101).mapToObj(id -> item(11L, id)).toList());
        lines.add(item(12L, 1L));
        when(items.findBySellerOrderIdIn(List.of(11L, 12L))).thenReturn(lines);
        when(catalog.getVariantSnapshots(any())).thenReturn(List.of());

        assertThat(queries.getCustomerOrder(100L, 1L).sellerOrders()).hasSize(2);

        var requests = ArgumentCaptor.forClass(VariantSnapshotBatchRequest.class);
        verify(catalog, org.mockito.Mockito.times(2)).getVariantSnapshots(requests.capture());
        assertThat(requests.getAllValues()).extracting(r -> r.variantIds().size()).containsExactly(100, 1);
        assertThat(requests.getAllValues().stream().flatMap(r -> r.variantIds().stream()).toList())
                .hasSize(101).doesNotHaveDuplicates();
    }

    private SellerOrderEntity seller(Long id, Long orderId) {
        SellerOrderEntity seller = new SellerOrderEntity();
        ReflectionTestUtils.setField(seller, "id", id);
        seller.setOrderId(orderId);
        seller.setStoreIdRef(10L);
        seller.setStatus("SHIPPING");
        return seller;
    }

    private OrderItemEntity item(Long sellerId, Long variantId) {
        OrderItemEntity item = new OrderItemEntity();
        item.setSellerOrderId(sellerId);
        item.setVariantIdRef(variantId);
        item.setQuantity(1);
        return item;
    }
}
