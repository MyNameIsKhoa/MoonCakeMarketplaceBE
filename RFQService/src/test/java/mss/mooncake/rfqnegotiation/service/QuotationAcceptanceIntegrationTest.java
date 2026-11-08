package mss.mooncake.rfqnegotiation.service;

import feign.FeignException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.Map;
import mss.mooncake.rfqnegotiation.client.*;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.*;
import mss.mooncake.rfqnegotiation.client.dto.commerce.OrderResponse;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.entity.*;
import mss.mooncake.rfqnegotiation.enums.*;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.repository.*;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="app.conversion-recovery.enabled=false")
class QuotationAcceptanceIntegrationTest {
    @Autowired QuotationApplicationService service;
    @Autowired QuotationManagementService management;
    @Autowired RfqRepository rfqs;
    @Autowired QuotationRepository quotations;
    @Autowired QuotationItemRepository items;
    @Autowired QuotationConversionRepository conversions;
    @MockitoBean IdentityServiceClient identity;
    @MockitoBean CatalogServiceClient catalog;
    @MockitoBean CommerceServiceClient commerce;
    Long id;

    @BeforeEach void setup() {
        conversions.deleteAll(); items.deleteAll(); quotations.deleteAll(); rfqs.deleteAll();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(11L,"buyer@test.vn",List.of()), null, List.of()));
        when(identity.getUserSummary(11L)).thenReturn(new UserSummaryResponse(11L,101L,"Buyer","buyer@test.vn","0123456789"));
        when(identity.getSupplierSummary(202L)).thenReturn(new SupplierSummaryResponse(202L,22L,"Supplier","TAX","VERIFIED"));
        when(identity.getAddressSummary(301L)).thenReturn(new AddressSummaryResponse(301L,101L,"Buyer","0123456789","Street",null,null,"HCM"));
        when(catalog.getStoreSummary(401L)).thenReturn(new StoreSummaryResponse(401L,202L,"STORE","Shop","shop",null,"ACTIVE"));
        var rfq=new RfqEntity(); rfq.setRfqCode("RFQ-ACCEPT-TEST"); rfq.setCustomerIdRef(101L); rfq.setTitle("Bánh trung thu");
        rfq.setQuantity(10); rfq.setStatus(RfqStatus.OPEN); rfq.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); rfqs.save(rfq);
        var q=new QuotationEntity(); q.setRfqId(rfq.getId()); q.setSupplierIdRef(202L); q.setStoreIdRef(401L);
        q.setVersionNo(1); q.setTotalAmount(new BigDecimal("1050.00")); q.setDepositPercent(new BigDecimal("30.00"));
        q.setShippingFee(new BigDecimal("50.00")); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        q.setProposedDeliveryDate(LocalDate.now(ZoneOffset.UTC).plusDays(10)); q.setStatus(QuotationStatus.SUBMITTED); quotations.save(q); id=q.getId();
        var item=new QuotationItemEntity(); item.setQuotationId(id); item.setItemNameSnapshot("Bánh custom"); item.setQuantity(10);
        item.setUnitPrice(new BigDecimal("100.00")); item.setDiscountAmount(BigDecimal.ZERO); item.setSubtotal(new BigDecimal("1000.00")); items.save(item);
        when(commerce.createOrderFromAcceptedQuotation(any())).thenReturn(new OrderResponse(501L,"PENDING_PAYMENT",601L,id,1));
    }
    @AfterEach void clearAuth() { SecurityContextHolder.clearContext(); }
    private static FeignException feignError(int status) {
        var request=feign.Request.create(feign.Request.HttpMethod.POST,"http://commerce/internal",Map.of(),null,StandardCharsets.UTF_8,null);
        return FeignException.errorStatus("CommerceServiceClient",feign.Response.builder().status(status).reason("test").request(request).headers(Map.of()).build());
    }

    @Test void acceptsOnceAndReplaysEvenAfterExpiry() {
        var first=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-one");
        assertThat(first.orderId()).isEqualTo("501"); assertThat(first.conversionStatus()).isEqualTo("COMPLETED");
        var q=quotations.findById(id).orElseThrow(); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1)); quotations.save(q);
        var replay=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-one");
        assertThat(replay).isEqualTo(first); assertThat(conversions.count()).isEqualTo(1);
        verify(commerce,times(1)).createOrderFromAcceptedQuotation(any());
    }
    @Test void preservesIntentWhenCommerceOutcomeIsUnknownAndRecoversFromLookup() {
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(new IllegalStateException("timeout"));
        var pending=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-timeout");
        assertThat(pending.conversionStatus()).isEqualTo("PENDING");
        assertThat(conversions.findByQuotationId(id).orElseThrow().getSnapshotJson()).contains("Bánh custom");
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"PENDING_PAYMENT",601L,id,1));
        service.recoverPendingConversions();
        assertThat(service.getOrderLink(id).orderId()).isEqualTo("501");
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }
    @Test void rejectsWrongBuyerAndChangedReplayPayload() {
        when(identity.getUserSummary(11L)).thenReturn(new UserSummaryResponse(11L,999L,"Other","other@test.vn",null));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"key")).isInstanceOf(ApiException.class);
        assertThat(conversions.count()).isZero();
        when(identity.getUserSummary(11L)).thenReturn(new UserSummaryResponse(11L,101L,"Buyer","buyer@test.vn",null));
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"key");
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,302L),"key")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsIncompleteProviderSnapshotBeforeRecordingAnIntent() {
        when(identity.getAddressSummary(301L)).thenReturn(new AddressSummaryResponse(301L,101L,"Buyer",null,"Street",null,null,"HCM"));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"invalid-address")).isInstanceOf(ApiException.class);
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }
    @Test void acceptsExistingValidQuotationAfterIntakeCloses() {
        var q=quotations.findById(id).orElseThrow();
        var rfq=rfqs.findById(q.getRfqId()).orElseThrow();
        rfq.setStatus(RfqStatus.CLOSED); rfq.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1)); rfqs.save(rfq);
        assertThat(service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"closed-intake").conversionStatus()).isEqualTo("COMPLETED");
    }
    @Test void rejectsStaleExpiredOrDecidedQuotationWithoutRecordingAnIntent() {
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(2,301L),"stale-version"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_VERSION_CONFLICT");
        var q=quotations.findById(id).orElseThrow(); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"expired"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_NOT_ACCEPTABLE");
        q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1)); q.setStatus(QuotationStatus.REJECTED); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"rejected"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_NOT_ACCEPTABLE");
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }
    @Test void rejectsDepositOrderWhoseRemainingDeadlineWouldAlreadyBeOver() {
        var q=quotations.findById(id).orElseThrow();
        q.setProposedDeliveryDate(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(3)); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"delivery-too-soon"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DELIVERY_TOO_SOON_FOR_REMAINING");
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }
    @Test void rejectsAcceptanceAfterRfqCancellation() {
        var rfq=rfqs.findById(quotations.findById(id).orElseThrow().getRfqId()).orElseThrow();
        rfq.setStatus(RfqStatus.CANCELLED); rfqs.save(rfq);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"cancelled-rfq"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_NOT_ACCEPTABLE");
        assertThat(conversions.count()).isZero();
    }
    @Test void definitiveCommerceRefusalFailsTheIntentAndStopsRecovery() {
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(feignError(409));
        when(commerce.getOrderByQuotation(id)).thenThrow(feignError(404));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-refused"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_CONVERSION_FAILED");
        assertThat(service.getOrderLink(id).conversionStatus()).isEqualTo("FAILED");
        service.recoverPendingConversions();
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-refused"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_CONVERSION_FAILED");
        verify(commerce,times(1)).createOrderFromAcceptedQuotation(any());
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.SUBMITTED);
    }
    @Test void refusalAfterALostCommitCompletesFromLookup() {
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(feignError(409));
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"PENDING_PAYMENT",601L,id,1));
        var accepted=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-lost-reply");
        assertThat(accepted.conversionStatus()).isEqualTo("COMPLETED"); assertThat(accepted.orderId()).isEqualTo("501");
    }
    @Test void submittedQuotationPastValidityIsReportedAsExpired() {
        assertThat(management.get(id).status()).isEqualTo(QuotationStatus.SUBMITTED);
        var q=quotations.findById(id).orElseThrow(); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)); quotations.save(q);
        assertThat(management.get(id).status()).isEqualTo(QuotationStatus.EXPIRED);
        assertThat(management.listForRfq(q.getRfqId(),0,20,null).items()).extracting("status").containsExactly(QuotationStatus.EXPIRED);
    }
    @Test void acceptsSeveralQuotationsOfOneRfqAsSeparateOrdersWithDistinctKeys() {
        var first=quotations.findById(id).orElseThrow();
        var rfq=rfqs.findById(first.getRfqId()).orElseThrow(); rfq.setQuantity(15); rfqs.save(rfq);
        var other=new QuotationEntity(); other.setRfqId(first.getRfqId()); other.setSupplierIdRef(203L); other.setStoreIdRef(402L);
        other.setVersionNo(1); other.setTotalAmount(new BigDecimal("550.00")); other.setDepositPercent(new BigDecimal("30.00"));
        other.setShippingFee(new BigDecimal("50.00")); other.setValidUntil(first.getValidUntil());
        other.setProposedDeliveryDate(first.getProposedDeliveryDate()); other.setStatus(QuotationStatus.SUBMITTED); quotations.save(other);
        var line=new QuotationItemEntity(); line.setQuotationId(other.getId()); line.setItemNameSnapshot("Bánh hộp quà"); line.setQuantity(5);
        line.setUnitPrice(new BigDecimal("100.00")); line.setDiscountAmount(BigDecimal.ZERO); line.setSubtotal(new BigDecimal("500.00")); items.save(line);
        when(identity.getSupplierSummary(203L)).thenReturn(new SupplierSummaryResponse(203L,23L,"Supplier 2","TAX2","VERIFIED"));
        when(catalog.getStoreSummary(402L)).thenReturn(new StoreSummaryResponse(402L,203L,"STORE2","Shop 2","shop-2",null,"ACTIVE"));
        when(commerce.createOrderFromAcceptedQuotation(argThat(r -> r!=null && other.getId().equals(r.quotationId()))))
                .thenReturn(new OrderResponse(502L,"PENDING_PAYMENT",602L,other.getId(),1));

        assertThat(service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-shared").orderId()).isEqualTo("501");
        assertThatThrownBy(()->service.acceptQuotation(other.getId(),new AcceptQuotationRequest(1,301L),"accept-shared"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        var second=service.acceptQuotation(other.getId(),new AcceptQuotationRequest(1,301L),"accept-second");
        assertThat(second.orderId()).isEqualTo("502"); assertThat(second.sellerOrderId()).isEqualTo("602");
        assertThat(conversions.count()).isEqualTo(2);
        assertThat(quotations.findAllById(List.of(id,other.getId()))).extracting("status")
                .containsOnly(QuotationStatus.ACCEPTED);
    }

    @Test void acceptsSeveralQuotationsOnlyUpToTheRfqQuantity() {
        Long six=quotation(203L,402L,6), five=quotation(204L,403L,5);
        service.acceptQuotation(six,new AcceptQuotationRequest(1,301L),"accept-six");
        var rfq=rfqs.findById(rfqId()).orElseThrow();
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.SUBMITTED);
        assertThatThrownBy(()->service.acceptQuotation(five,new AcceptQuotationRequest(1,301L),"accept-five"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_QUANTITY_EXCEEDED");
        assertThat(conversions.findByQuotationId(five)).isEmpty();
    }
    @Test void reachingTheRfqQuantityClosesItAndDeclinesTheOtherQuotations() {
        Long other=quotation(203L,402L,4);
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-all");
        var rfq=rfqs.findById(rfqId()).orElseThrow();
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.CLOSED);
        assertThat(rfq.isAutoClosed()).isTrue();
        var declined=quotations.findById(other).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(QuotationStatus.REJECTED);
        assertThat(declined.getDecisionReason()).contains("đủ số lượng");
    }
    @Test void cancelledOrderMarksTheQuotationAndReopensTheAutoClosedRfq() {
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-then-cancel");
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"CANCELLED",601L,id,1));
        service.syncAcceptedOrders();
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ORDER_CANCELLED);
        var rfq=rfqs.findById(rfqId()).orElseThrow();
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
        assertThat(rfq.isAutoClosed()).isFalse();
        Long next=quotation(203L,402L,10);
        assertThat(service.acceptQuotation(next,new AcceptQuotationRequest(1,301L),"accept-next").conversionStatus()).isEqualTo("COMPLETED");
    }
    @Test void syncLeavesLiveOrdersAndBuyerClosedRfqsAlone() {
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-live");
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"AWAITING_DEPOSIT",601L,id,1));
        service.syncAcceptedOrders();
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        var rfq=rfqs.findById(rfqId()).orElseThrow(); rfq.setAutoClosed(false); rfqs.save(rfq);
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"CANCELLED",601L,id,1));
        service.syncAcceptedOrders();
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ORDER_CANCELLED);
        assertThat(rfqs.findById(rfqId()).orElseThrow().getStatus()).isEqualTo(RfqStatus.CLOSED);
    }

    @Test void unknownAddressIsReportedAsNotFound() {
        when(identity.getAddressSummary(999L)).thenThrow(feignError(404));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,999L),"no-address"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ADDRESS_NOT_FOUND");
    }
    @Test void buyerCannotAcceptAQuotationFromTheirOwnSupplierAccount() {
        when(identity.getSupplierSummary(202L)).thenReturn(new SupplierSummaryResponse(202L,11L,"Self","TAX","VERIFIED"));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"self"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SELF_DEALING");
        verifyNoInteractions(commerce);
    }
    @Test void reopeningRestoresQuotationsDeclinedForQuantityAndReportsTheOrderStatus() {
        Long other=quotation(203L,402L,4);
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-full");
        assertThat(quotations.findById(other).orElseThrow().getStatus()).isEqualTo(QuotationStatus.REJECTED);
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"CANCELLED",601L,id,1));
        service.syncAcceptedOrders();
        var restored=quotations.findById(other).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(QuotationStatus.SUBMITTED);
        assertThat(restored.getDecisionReason()).isNull();
        assertThat(service.getOrderLink(id).orderStatus()).isEqualTo("CANCELLED");
    }
    @Test void quotationOfASuspendedSupplierCannotBeAccepted() {
        when(identity.getSupplierSummary(202L)).thenReturn(new SupplierSummaryResponse(202L,22L,"Supplier","TAX","SUSPENDED"));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"suspended"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SUPPLIER_NOT_VERIFIED");
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }

    @Test void acceptedQuotationNotifiesItsSupplierAndTheDeclinedOnesOnce() {
        Long other=quotation(203L,402L,4);
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-notify");
        var sent=sentNotifications();
        assertThat(sent).filteredOn(n -> "QUOTATION_ACCEPTED".equals(n.type())).singleElement().satisfies(n -> {
            assertThat(n.supplierId()).isEqualTo(202L);
            assertThat(n.target()).containsEntry("kind","ORDER").containsEntry("id","601").containsEntry("role","SELLER");
        });
        assertThat(sent).filteredOn(n -> "QUOTATION_NOT_SELECTED".equals(n.type()))
                .extracting(CreateNotificationRequest::supplierId).containsExactly(203L);
        assertThat(quotations.findById(other).orElseThrow().getStatus()).isEqualTo(QuotationStatus.REJECTED);
        // Replaying the same acceptance tells nobody again.
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-notify");
        assertThat(sentNotifications()).hasSize(sent.size());
    }
    @Test void notificationFailureNeverBreaksTheAcceptance() {
        doThrow(new IllegalStateException("identity down")).when(identity).createNotification(any());
        var accepted=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-identity-down");
        assertThat(accepted.conversionStatus()).isEqualTo("COMPLETED");
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
        verify(identity,atLeastOnce()).createNotification(any());
    }
    @Test void cancelledOrderFoundBySyncNotifiesBuyerAndSupplierOnce() {
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-cancel-notify");
        clearInvocations(identity);
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"CANCELLED",601L,id,1));
        service.syncAcceptedOrders();
        service.syncAcceptedOrders();
        var sent=sentNotifications();
        assertThat(sent).extracting(CreateNotificationRequest::type).containsOnly("ORDER_CANCELLED");
        assertThat(sent).hasSize(2);
        assertThat(sent).filteredOn(n -> n.customerId()!=null).singleElement().satisfies(n -> {
            assertThat(n.customerId()).isEqualTo(101L);
            assertThat(n.target()).containsEntry("kind","ORDER").containsEntry("id","501");
        });
        assertThat(sent).filteredOn(n -> n.supplierId()!=null).singleElement()
                .satisfies(n -> assertThat(n.target()).containsEntry("id","601").containsEntry("role","SELLER"));
    }
    @Test void failedConversionNotifiesNobody() {
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(feignError(409));
        when(commerce.getOrderByQuotation(id)).thenThrow(feignError(404));
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-refused-notify"))
                .isInstanceOf(ApiException.class);
        verify(identity,never()).createNotification(any());
    }

    private List<CreateNotificationRequest> sentNotifications() {
        var captor=org.mockito.ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity,atLeast(0)).createNotification(captor.capture());
        return captor.getAllValues();
    }
    private Long rfqId() { return quotations.findById(id).orElseThrow().getRfqId(); }
    /** Another store's SUBMITTED quotation of `quantity` units at 100 each plus 50 shipping. */
    private Long quotation(long supplierId, long storeId, int quantity) {
        var first=quotations.findById(id).orElseThrow();
        var q=new QuotationEntity(); q.setRfqId(first.getRfqId()); q.setSupplierIdRef(supplierId); q.setStoreIdRef(storeId);
        q.setVersionNo(1); q.setTotalAmount(BigDecimal.valueOf(quantity*100L+50).setScale(2)); q.setDepositPercent(new BigDecimal("30.00"));
        q.setShippingFee(new BigDecimal("50.00")); q.setValidUntil(first.getValidUntil());
        q.setProposedDeliveryDate(first.getProposedDeliveryDate()); q.setStatus(QuotationStatus.SUBMITTED); quotations.save(q);
        var line=new QuotationItemEntity(); line.setQuotationId(q.getId()); line.setItemNameSnapshot("Bánh "+quantity); line.setQuantity(quantity);
        line.setUnitPrice(new BigDecimal("100.00")); line.setDiscountAmount(BigDecimal.ZERO); line.setSubtotal(BigDecimal.valueOf(quantity*100L).setScale(2)); items.save(line);
        when(identity.getSupplierSummary(supplierId)).thenReturn(new SupplierSummaryResponse(supplierId,supplierId-180,"Supplier "+supplierId,"TAX"+supplierId,"VERIFIED"));
        when(catalog.getStoreSummary(storeId)).thenReturn(new StoreSummaryResponse(storeId,supplierId,"STORE"+storeId,"Shop "+storeId,"shop-"+storeId,null,"ACTIVE"));
        Long quotationId=q.getId();
        when(commerce.createOrderFromAcceptedQuotation(argThat(r -> r!=null && quotationId.equals(r.quotationId()))))
                .thenReturn(new OrderResponse(500L+quotationId,"AWAITING_DEPOSIT",600L+quotationId,quotationId,1));
        return quotationId;
    }
}
