package mss.mooncake.rfqnegotiation.service;

import feign.FeignException;
import feign.Request;
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
import mss.mooncake.rfqnegotiation.dto.request.CreateOrderFromAcceptedQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import mss.mooncake.rfqnegotiation.entity.*;
import mss.mooncake.rfqnegotiation.enums.*;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.repository.*;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
        when(identity.getSupplierByUser(11L)).thenThrow(feignError(404));
        when(identity.getSupplierSummary(202L)).thenReturn(new SupplierSummaryResponse(202L,22L,"Supplier","TAX","VERIFIED"));
        when(identity.getAddressSummary(301L)).thenReturn(new AddressSummaryResponse(301L,101L,"Buyer","0123456789","Street",null,null,"HCM"));
        when(catalog.getStoreSummary(401L)).thenReturn(new StoreSummaryResponse(401L,202L,"STORE","Shop","shop",null,"ACTIVE"));
        RfqEntity rfq=new RfqEntity(); rfq.setRfqCode("RFQ-ACCEPT-TEST"); rfq.setCustomerIdRef(101L); rfq.setTitle("Bánh trung thu");
        rfq.setQuantity(10); rfq.setStatus(RfqStatus.OPEN); rfq.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); rfqs.save(rfq);
        QuotationEntity q=new QuotationEntity(); q.setRfqId(rfq.getId()); q.setSupplierIdRef(202L); q.setStoreIdRef(401L);
        q.setVersionNo(1); q.setTotalAmount(new BigDecimal("1050.00")); q.setDepositPercent(new BigDecimal("30.00"));
        q.setShippingFee(new BigDecimal("50.00")); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        q.setProposedDeliveryDate(LocalDate.now(ZoneOffset.UTC).plusDays(10)); q.setStatus(QuotationStatus.SUBMITTED); quotations.save(q); id=q.getId();
        QuotationItemEntity item=new QuotationItemEntity(); item.setQuotationId(id); item.setItemNameSnapshot("Bánh custom"); item.setQuantity(10);
        item.setUnitPrice(new BigDecimal("100.00")); item.setDiscountAmount(BigDecimal.ZERO); item.setSubtotal(new BigDecimal("1000.00")); items.save(item);
        when(commerce.createOrderFromAcceptedQuotation(any())).thenReturn(new OrderResponse(501L,"PENDING_PAYMENT",601L,id,1));
    }
    @AfterEach void clearAuth() { SecurityContextHolder.clearContext(); }
    private static FeignException feignError(int status) {
        Request request=feign.Request.create(feign.Request.HttpMethod.POST,"http://commerce/internal",Map.of(),null,StandardCharsets.UTF_8,null);
        return FeignException.errorStatus("CommerceServiceClient",feign.Response.builder().status(status).reason("test").request(request).headers(Map.of()).build());
    }

    @Test void acceptsOnceAndReplaysEvenAfterExpiry() {
        QuotationAcceptedResponse first=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-one");
        assertThat(first.orderId()).isEqualTo("501"); assertThat(first.conversionStatus()).isEqualTo("COMPLETED");
        QuotationEntity q=quotations.findById(id).orElseThrow(); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).minusDays(1)); quotations.save(q);
        QuotationAcceptedResponse replay=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-one");
        assertThat(replay).isEqualTo(first); assertThat(conversions.count()).isEqualTo(1);
        verify(commerce,times(1)).createOrderFromAcceptedQuotation(any());
    }
    @Test void sendsTheSavedVariantImageAndReusesItOnClientAndRecoveryRetries() {
        QuotationItemEntity item=items.findByQuotationIdOrderByIdAsc(id).getFirst();
        item.setVariantIdRef(50L); item.setImageUrlSnapshot("/images/saved-variant.jpg"); items.save(item);
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(new IllegalStateException("timeout"));
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"image-retry");
        String persisted=conversions.findByQuotationId(id).orElseThrow().getSnapshotJson();
        // Even an accidental later item change cannot change the durable conversion payload.
        item.setImageUrlSnapshot("/images/changed-later.jpg"); items.save(item);
        when(commerce.getOrderByQuotation(id)).thenThrow(feignError(404));
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"image-retry");
        service.recoverPendingConversions();

        ArgumentCaptor<CreateOrderFromAcceptedQuotationRequest> sent=org.mockito.ArgumentCaptor.forClass(CreateOrderFromAcceptedQuotationRequest.class);
        verify(commerce,times(3)).createOrderFromAcceptedQuotation(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(payload -> {
            assertThat(payload).isEqualTo(sent.getAllValues().getFirst());
            assertThat(payload.items().getFirst().imageUrlSnapshot()).isEqualTo("/images/saved-variant.jpg");
        });
        assertThat(conversions.findByQuotationId(id).orElseThrow().getSnapshotJson()).isEqualTo(persisted);
        verify(catalog,never()).getProductSnapshot(any(),any());
    }
    @Test void legacyCustomItemUsesTheSharedPlaceholderWhenFirstAccepted() {
        assertThat(items.findByQuotationIdOrderByIdAsc(id).getFirst().getImageUrlSnapshot()).isNull();
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"legacy-custom");
        ArgumentCaptor<CreateOrderFromAcceptedQuotationRequest> sent=org.mockito.ArgumentCaptor.forClass(CreateOrderFromAcceptedQuotationRequest.class);
        verify(commerce).createOrderFromAcceptedQuotation(sent.capture());
        assertThat(sent.getValue().items().getFirst().imageUrlSnapshot()).isEqualTo("/images/placeholders/custom-order.svg");
        assertThat(items.findByQuotationIdOrderByIdAsc(id).getFirst().getImageUrlSnapshot()).isNull();
    }
    @Test void historicalVariantWithoutImageStaysNullWithoutLiveCatalogLookup() {
        QuotationItemEntity item=items.findByQuotationIdOrderByIdAsc(id).getFirst();
        item.setVariantIdRef(50L); items.save(item);
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"legacy-variant");
        ArgumentCaptor<CreateOrderFromAcceptedQuotationRequest> sent=org.mockito.ArgumentCaptor.forClass(CreateOrderFromAcceptedQuotationRequest.class);
        verify(commerce).createOrderFromAcceptedQuotation(sent.capture());
        assertThat(sent.getValue().items().getFirst().imageUrlSnapshot()).isNull();
        verify(catalog,never()).getProductSnapshot(any(),any());
    }
    @Test void recoveryOfAPreMigrationCustomConversionPreservesItsNullImage() {
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(new IllegalStateException("timeout"));
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"old-conversion");
        QuotationConversionEntity conversion=conversions.findByQuotationId(id).orElseThrow();
        String oldSnapshot=conversion.getSnapshotJson().replace("\"imageUrlSnapshot\":\"/images/placeholders/custom-order.svg\"", "\"imageUrlSnapshot\":null");
        assertThat(oldSnapshot).isNotEqualTo(conversion.getSnapshotJson());
        conversion.setSnapshotJson(oldSnapshot); conversions.save(conversion);
        clearInvocations(commerce);
        when(commerce.getOrderByQuotation(id)).thenThrow(feignError(404));
        service.recoverPendingConversions();
        ArgumentCaptor<CreateOrderFromAcceptedQuotationRequest> sent=org.mockito.ArgumentCaptor.forClass(CreateOrderFromAcceptedQuotationRequest.class);
        verify(commerce).createOrderFromAcceptedQuotation(sent.capture());
        assertThat(sent.getValue().items().getFirst().imageUrlSnapshot()).isNull();
        assertThat(conversions.findByQuotationId(id).orElseThrow().getSnapshotJson()).isEqualTo(oldSnapshot);
    }
    @Test void preservesIntentWhenCommerceOutcomeIsUnknownAndRecoversFromLookup() {
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(new IllegalStateException("timeout"));
        QuotationAcceptedResponse pending=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-timeout");
        assertThat(pending.conversionStatus()).isEqualTo("PENDING");
        assertThat(conversions.findByQuotationId(id).orElseThrow().getSnapshotJson()).contains("Bánh custom");
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"PENDING_PAYMENT",601L,id,1));
        service.recoverPendingConversions();
        assertThat(service.getOrderLink(id).orderId()).isEqualTo("501");
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }
    @Test void passesQuotedItemImageToTheOrder() {
        QuotationItemEntity item=items.findByQuotationIdOrderByIdAsc(id).getFirst(); item.setImageUrlSnapshot("/images/demo/hop-qua.webp"); items.save(item);
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-image");
        ArgumentCaptor<CreateOrderFromAcceptedQuotationRequest> sent=org.mockito.ArgumentCaptor.forClass(mss.mooncake.rfqnegotiation.dto.request.CreateOrderFromAcceptedQuotationRequest.class);
        verify(commerce).createOrderFromAcceptedQuotation(sent.capture());
        assertThat(sent.getValue().items().getFirst().imageUrlSnapshot()).isEqualTo("/images/demo/hop-qua.webp");
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
        QuotationEntity q=quotations.findById(id).orElseThrow();
        RfqEntity rfq=rfqs.findById(q.getRfqId()).orElseThrow();
        rfq.setStatus(RfqStatus.CLOSED); rfq.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1)); rfqs.save(rfq);
        assertThat(service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"closed-intake").conversionStatus()).isEqualTo("COMPLETED");
    }
    @Test void rejectsStaleExpiredOrDecidedQuotationWithoutRecordingAnIntent() {
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(2,301L),"stale-version"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_VERSION_CONFLICT");
        QuotationEntity q=quotations.findById(id).orElseThrow(); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"expired"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_NOT_ACCEPTABLE");
        q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1)); q.setStatus(QuotationStatus.REJECTED); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"rejected"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("QUOTATION_NOT_ACCEPTABLE");
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }
    @Test void rejectsDepositOrderWhoseRemainingDeadlineWouldAlreadyBeOver() {
        QuotationEntity q=quotations.findById(id).orElseThrow();
        q.setProposedDeliveryDate(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(3)); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"delivery-too-soon"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DELIVERY_TOO_SOON_FOR_REMAINING");
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }
    @Test void rejectsFullPaymentOrderDeliveredBeforeItsPaymentWindowEnds() {
        // 0% deposit = one FULL payment with 48h to pay; delivery 2 days out could come before the money.
        QuotationEntity q=quotations.findById(id).orElseThrow(); q.setDepositPercent(new BigDecimal("0.00"));
        q.setProposedDeliveryDate(LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(2)); quotations.save(q);
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"full-too-soon"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("DELIVERY_TOO_SOON_FOR_PAYMENT");
        assertThat(conversions.count()).isZero();
        verifyNoInteractions(commerce);
    }
    @Test void rejectsAcceptanceAfterRfqCancellation() {
        RfqEntity rfq=rfqs.findById(quotations.findById(id).orElseThrow().getRfqId()).orElseThrow();
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
        QuotationAcceptedResponse accepted=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-lost-reply");
        assertThat(accepted.conversionStatus()).isEqualTo("COMPLETED"); assertThat(accepted.orderId()).isEqualTo("501");
    }
    @Test void submittedQuotationPastValidityIsReportedAsExpired() {
        assertThat(management.get(id).status()).isEqualTo(QuotationStatus.SUBMITTED);
        QuotationEntity q=quotations.findById(id).orElseThrow(); q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)); quotations.save(q);
        assertThat(management.get(id).status()).isEqualTo(QuotationStatus.EXPIRED);
        assertThat(management.listForRfq(q.getRfqId(),0,20,null).items()).extracting("status").containsExactly(QuotationStatus.EXPIRED);
    }
    @Test void firstAcceptanceAwardsTheRfqEvenBelowItsQuantityAndRefusesASecond() {
        Long six=quotation(203L,402L,6);
        service.acceptQuotation(six,new AcceptQuotationRequest(1,301L),"accept-six");
        RfqEntity rfq=rfqs.findById(rfqId()).orElseThrow();
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.CLOSED);
        assertThat(rfq.isAutoClosed()).isTrue();
        QuotationEntity declined=quotations.findById(id).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(QuotationStatus.REJECTED);
        assertThat(declined.getDecisionReason()).contains("chốt báo giá khác");
        // A key stays bound to its quotation; a fresh key meets the award.
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-six"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThatThrownBy(()->service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-second"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_ALREADY_AWARDED");
        assertThat(conversions.count()).isEqualTo(1);
    }
    @Test void pendingAcceptanceAlreadyBlocksAnotherSupplier() {
        Long other=quotation(203L,402L,4);
        when(commerce.createOrderFromAcceptedQuotation(any())).thenThrow(new IllegalStateException("timeout"));
        assertThat(service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-pending").conversionStatus()).isEqualTo("PENDING");
        assertThatThrownBy(()->service.acceptQuotation(other,new AcceptQuotationRequest(1,301L),"accept-other"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_ALREADY_AWARDED");
        assertThat(conversions.findByQuotationId(other)).isEmpty();
    }
    @Test void reachingTheRfqQuantityClosesItAndDeclinesTheOtherQuotations() {
        Long other=quotation(203L,402L,4);
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-all");
        RfqEntity rfq=rfqs.findById(rfqId()).orElseThrow();
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.CLOSED);
        assertThat(rfq.isAutoClosed()).isTrue();
        QuotationEntity declined=quotations.findById(other).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(QuotationStatus.REJECTED);
        assertThat(declined.getDecisionReason()).contains("chốt báo giá khác");
    }
    @Test void cancelledOrderMarksTheQuotationAndReopensTheAutoClosedRfq() {
        service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-then-cancel");
        when(commerce.getOrderByQuotation(id)).thenReturn(new OrderResponse(501L,"CANCELLED",601L,id,1));
        service.syncAcceptedOrders();
        assertThat(quotations.findById(id).orElseThrow().getStatus()).isEqualTo(QuotationStatus.ORDER_CANCELLED);
        RfqEntity rfq=rfqs.findById(rfqId()).orElseThrow();
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
        RfqEntity rfq=rfqs.findById(rfqId()).orElseThrow(); rfq.setAutoClosed(false); rfqs.save(rfq);
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
        QuotationEntity restored=quotations.findById(other).orElseThrow();
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
        List<CreateNotificationRequest> sent=sentNotifications();
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
        QuotationAcceptedResponse accepted=service.acceptQuotation(id,new AcceptQuotationRequest(1,301L),"accept-identity-down");
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
        List<CreateNotificationRequest> sent=sentNotifications();
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
        ArgumentCaptor<CreateNotificationRequest> captor=org.mockito.ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity,atLeast(0)).createNotification(captor.capture());
        return captor.getAllValues();
    }
    private Long rfqId() { return quotations.findById(id).orElseThrow().getRfqId(); }
    /** Another store's SUBMITTED quotation of `quantity` units at 100 each plus 50 shipping. */
    private Long quotation(long supplierId, long storeId, int quantity) {
        QuotationEntity first=quotations.findById(id).orElseThrow();
        QuotationEntity q=new QuotationEntity(); q.setRfqId(first.getRfqId()); q.setSupplierIdRef(supplierId); q.setStoreIdRef(storeId);
        q.setVersionNo(1); q.setTotalAmount(BigDecimal.valueOf(quantity*100L+50).setScale(2)); q.setDepositPercent(new BigDecimal("30.00"));
        q.setShippingFee(new BigDecimal("50.00")); q.setValidUntil(first.getValidUntil());
        q.setProposedDeliveryDate(first.getProposedDeliveryDate()); q.setStatus(QuotationStatus.SUBMITTED); quotations.save(q);
        QuotationItemEntity line=new QuotationItemEntity(); line.setQuotationId(q.getId()); line.setItemNameSnapshot("Bánh "+quantity); line.setQuantity(quantity);
        line.setUnitPrice(new BigDecimal("100.00")); line.setDiscountAmount(BigDecimal.ZERO); line.setSubtotal(BigDecimal.valueOf(quantity*100L).setScale(2)); items.save(line);
        when(identity.getSupplierSummary(supplierId)).thenReturn(new SupplierSummaryResponse(supplierId,supplierId-180,"Supplier "+supplierId,"TAX"+supplierId,"VERIFIED"));
        when(catalog.getStoreSummary(storeId)).thenReturn(new StoreSummaryResponse(storeId,supplierId,"STORE"+storeId,"Shop "+storeId,"shop-"+storeId,null,"ACTIVE"));
        Long quotationId=q.getId();
        when(commerce.createOrderFromAcceptedQuotation(argThat(r -> r!=null && quotationId.equals(r.quotationId()))))
                .thenReturn(new OrderResponse(500L+quotationId,"AWAITING_DEPOSIT",600L+quotationId,quotationId,1));
        return quotationId;
    }
}
