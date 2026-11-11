package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateRfqRequest;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.entity.RfqTargetStoreEntity;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.notification.RfqNotifier;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.repository.RfqTargetStoreRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.impl.RfqApplicationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RfqApplicationServiceTest {
    @Mock private RfqRepository rfqs;
    @Mock private QuotationRepository quotations;
    @Mock private RfqTargetStoreRepository targets;
    @Mock private CatalogServiceClient catalog;
    @Mock private ActorContext actor;
    @Mock private RfqNotifier notifier;
    private ValidatorFactory validators;
    private RfqApplicationService service;

    @BeforeEach
    void setUp() {
        validators = Validation.buildDefaultValidatorFactory();
        service = new RfqApplicationServiceImpl(rfqs, quotations, targets, catalog, actor, validators.getValidator(), notifier);
        lenient().when(actor.optionalSupplierId()).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        validators.close();
    }

    @Test
    void createsPublishedRfqUsingAuthenticatedCustomerAndUtcTimestamp() {
        when(actor.customerId()).thenReturn(17L);
        when(rfqs.save(any())).thenAnswer(invocation -> {
            RfqEntity rfq = invocation.getArgument(0);
            ReflectionTestUtils.setField(rfq, "id", 91L);
            return rfq;
        });
        RfqResponse created = service.create(request(100, new BigDecimal("3500000.00")));
        assertThat(created.rfqId()).isEqualTo("91");
        assertThat(created.customerId()).isEqualTo("17");
        assertThat(created.status()).isEqualTo(RfqStatus.OPEN);
        assertThat(created.budgetAmount()).isEqualTo("3500000.00");
        assertThat(created.createdAt()).endsWith("Z");
        assertThat(created.title()).isEqualTo("Mooncakes for company");
        assertThat(created.audience()).isEqualTo(RfqAudience.ALL);
        assertThat(created.targetStores()).isNull();
        verifyNoInteractions(catalog, targets);
    }

    @Test
    void createKeepsTheBuyerNameForConversations() {
        when(actor.customerId()).thenReturn(17L);
        when(actor.fullName()).thenReturn("Công ty Demo B2B");
        when(rfqs.save(any())).thenAnswer(invocation -> {
            RfqEntity rfq = invocation.getArgument(0);
            ReflectionTestUtils.setField(rfq, "id", 91L);
            return rfq;
        });

        service.create(request(100, null));

        ArgumentCaptor<RfqEntity> saved = ArgumentCaptor.forClass(RfqEntity.class);
        verify(rfqs).save(saved.capture());
        assertThat(saved.getValue().getCustomerNameSnapshot()).isEqualTo("Công ty Demo B2B");
    }

    @Test
    void createsRfqAddressedOnceToEachSelectedActiveStore() {
        when(actor.customerId()).thenReturn(17L);
        when(catalog.getStoreSummary(30L)).thenReturn(store(30L, 20L, "Kinh Do", "ACTIVE"));
        when(catalog.getStoreSummary(31L)).thenReturn(store(31L, 21L, "Givral", "ACTIVE"));
        when(rfqs.save(any())).thenAnswer(invocation -> {
            RfqEntity rfq = invocation.getArgument(0);
            ReflectionTestUtils.setField(rfq, "id", 91L);
            return rfq;
        });

        RfqResponse created = service.create(request(100, null, List.of(30L, 31L, 30L)));

        assertThat(created.audience()).isEqualTo(RfqAudience.SELECTED_STORES);
        assertThat(created.targetStores()).extracting(RfqResponse.TargetStore::storeId).containsExactly("30", "31");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RfqTargetStoreEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(targets).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(RfqTargetStoreEntity::getRfqId).containsOnly(91L);
        assertThat(saved.getValue()).extracting(RfqTargetStoreEntity::getSupplierIdRef).containsExactly(20L, 21L);
        assertThat(saved.getValue()).extracting(RfqTargetStoreEntity::getStoreNameSnapshot).containsExactly("Kinh Do", "Givral");
        verify(catalog, times(2)).getStoreSummary(anyLong());
    }

    @Test
    void privateRfqNotifiesEachInvitedSupplierOnceAndPublicRfqNobody() {
        when(actor.customerId()).thenReturn(17L);
        when(catalog.getStoreSummary(30L)).thenReturn(store(30L, 20L, "Kinh Do", "ACTIVE"));
        when(catalog.getStoreSummary(31L)).thenReturn(store(31L, 20L, "Kinh Do Q3", "ACTIVE"));
        when(catalog.getStoreSummary(32L)).thenReturn(store(32L, 21L, "Givral", "ACTIVE"));
        when(rfqs.save(any())).thenAnswer(invocation -> {
            RfqEntity rfq = invocation.getArgument(0);
            ReflectionTestUtils.setField(rfq, "id", 91L);
            return rfq;
        });

        service.create(request(100, null, List.of(30L, 31L, 32L)));
        verify(notifier).rfqInvited(any(RfqEntity.class), eq(List.of(20L, 21L)));

        service.create(request(100, null));
        verify(notifier, times(1)).rfqInvited(any(), any());
    }

    @Test
    void rejectsInactiveTargetStoreAndMoreThanTwentyStoresBeforePersisting() {
        lenient().when(actor.customerId()).thenReturn(17L);
        when(catalog.getStoreSummary(30L)).thenReturn(store(30L, 20L, "Closed bakery", "INACTIVE"));
        assertThatThrownBy(() -> service.create(request(100, null, List.of(30L))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("STORE_NOT_ACTIVE");
        List<Long> tooMany = LongStream.rangeClosed(1, 21).boxed().toList();
        assertThatThrownBy(() -> service.create(request(100, null, tooMany)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(rfqs, targets);
    }

    @Test
    void selectedRfqIsHiddenFromUninvitedSuppliersAndShowsInvitedOnesOnlyTheirOwnStores() {
        RfqEntity rfq = entity(RfqStatus.OPEN);
        rfq.setAudience(RfqAudience.SELECTED_STORES);
        when(rfqs.findById(91L)).thenReturn(Optional.of(rfq));
        when(actor.optionalSupplierId()).thenReturn(22L, 20L);
        when(targets.findByRfqIdOrderByIdAsc(91L)).thenReturn(List.of(
                new RfqTargetStoreEntity(91L, 30L, 20L, "Kinh Do"), new RfqTargetStoreEntity(91L, 31L, 21L, "Givral")));

        assertThatThrownBy(() -> service.get(91L)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RFQ_NOT_FOUND");
        RfqResponse invited = service.get(91L);
        assertThat(invited.targetStores()).extracting(RfqResponse.TargetStore::storeName).containsExactly("Kinh Do");
        assertThat(invited.customerId()).isNull();
    }

    @Test
    void ownerSeesEveryInvitedStore() {
        RfqEntity rfq = entity(RfqStatus.OPEN);
        rfq.setAudience(RfqAudience.SELECTED_STORES);
        when(rfqs.findById(91L)).thenReturn(Optional.of(rfq));
        when(actor.optionalCustomerId()).thenReturn(17L);
        when(targets.findByRfqIdOrderByIdAsc(91L)).thenReturn(List.of(
                new RfqTargetStoreEntity(91L, 30L, 20L, "Kinh Do"), new RfqTargetStoreEntity(91L, 31L, 21L, "Givral")));

        assertThat(service.get(91L).targetStores()).extracting(RfqResponse.TargetStore::storeName)
                .containsExactly("Kinh Do", "Givral");
    }

    @Test
    void publicDetailOmitsCustomerAndPrivateDeliveryAndCustomizationData() {
        when(rfqs.findById(91L)).thenReturn(Optional.of(entity(RfqStatus.OPEN)));
        when(actor.optionalSupplierId()).thenReturn(20L);
        RfqResponse detail = service.get(91L);
        assertThat(detail.customerId()).isNull();
        assertThat(detail.deliveryLocation()).isNull();
        assertThat(detail.customizationRequirements()).isNull();
        assertThat(detail.description()).isEqualTo("Public product requirements");
    }

    @Test
    void ownerCanReadPrivateInformationAndCancelledRfq() {
        when(rfqs.findById(91L)).thenReturn(Optional.of(entity(RfqStatus.CANCELLED)));
        when(actor.optionalCustomerId()).thenReturn(17L);
        RfqResponse detail = service.get(91L);
        assertThat(detail.customerId()).isEqualTo("17");
        assertThat(detail.deliveryLocation()).isEqualTo("Private company address");
        assertThat(detail.customizationRequirements()).isEqualTo("Private design requirements");
    }

    @Test
    void draftAndCancelledRfqAreNotVisibleToOtherCustomers() {
        when(rfqs.findById(91L)).thenReturn(Optional.of(entity(RfqStatus.DRAFT)));
        when(actor.optionalCustomerId()).thenReturn(18L);
        assertThatThrownBy(() -> service.get(91L)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RFQ_NOT_FOUND");
    }

    @Test
    void rejectsInvalidQuantityAndMoneyPrecisionBeforePersisting() {
        assertThatThrownBy(() -> service.create(request(0, BigDecimal.ONE)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        assertThatThrownBy(() -> service.create(request(20, new BigDecimal("2.001"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
        verifyNoInteractions(rfqs);
    }

    @Test
    void patchDistinguishesMissingFieldsFromExplicitNull() {
        when(actor.customerId()).thenReturn(17L);
        RfqEntity rfq = entity(RfqStatus.OPEN);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        Map<String, Object> patch = new HashMap<>();
        patch.put("budgetAmount", null);
        patch.put("title", "Updated title");
        RfqResponse updated = service.update(91L, patch);
        assertThat(updated.budgetAmount()).isNull();
        assertThat(updated.title()).isEqualTo("Updated title");
        assertThat(updated.description()).isEqualTo("Public product requirements");
    }

    @Test
    void patchRejectsNullMandatoryUnknownAndReadOnlyFields() {
        when(actor.customerId()).thenReturn(17L);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(entity(RfqStatus.OPEN)));
        Map<String, Object> nullTitle = new HashMap<>();
        nullTitle.put("title", null);
        assertThatThrownBy(() -> service.update(91L, nullTitle)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.update(91L, Map.of("customerId", "99")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.update(91L, Map.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void cannotEditOnceAnySupplierHasCreatedQuotation() {
        when(actor.customerId()).thenReturn(17L);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(entity(RfqStatus.OPEN)));
        when(quotations.existsByRfqId(91L)).thenReturn(true);
        assertThatThrownBy(() -> service.update(91L, Map.of("quantity", 200)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_HAS_QUOTATIONS");
    }

    @Test
    void forbiddenCustomerCannotCloseOtherCustomersRfq() {
        when(actor.customerId()).thenReturn(18L);
        RfqEntity rfq = entity(RfqStatus.OPEN);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        assertThatThrownBy(() -> service.close(91L)).isInstanceOf(ApiException.class);
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
    }

    @Test
    void buyerClosingAnRfqTheSystemClosedKeepsItClosedForGood() {
        when(actor.customerId()).thenReturn(17L);
        RfqEntity rfq = entity(RfqStatus.CLOSED);
        rfq.setAutoClosed(true);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        assertThat(service.close(91L).status()).isEqualTo(RfqStatus.CLOSED);
        assertThat(rfq.isAutoClosed()).isFalse();
    }

    @Test
    void cancelIsRefusedWhileAQuotationIsTurningOrHasTurnedIntoAnOrder() {
        when(actor.customerId()).thenReturn(17L);
        RfqEntity rfq = entity(RfqStatus.OPEN);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        when(quotations.hasLiveAcceptance(91L)).thenReturn(true);
        assertThatThrownBy(() -> service.cancel(91L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_HAS_ORDERS");
        assertThat(rfq.getStatus()).isEqualTo(RfqStatus.OPEN);
    }

    @Test
    void cancellingDeclinesTheQuotationsStillWaiting() {
        when(actor.customerId()).thenReturn(17L);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(entity(RfqStatus.OPEN)));
        QuotationEntity waiting = new mss.mooncake.rfqnegotiation.entity.QuotationEntity();
        waiting.setStatus(mss.mooncake.rfqnegotiation.enums.QuotationStatus.SUBMITTED);
        when(quotations.findByRfqIdAndStatus(91L, mss.mooncake.rfqnegotiation.enums.QuotationStatus.SUBMITTED))
                .thenReturn(List.of(waiting));
        service.cancel(91L);
        assertThat(waiting.getStatus()).isEqualTo(mss.mooncake.rfqnegotiation.enums.QuotationStatus.REJECTED);
        assertThat(waiting.getDecisionReason()).contains("hủy");
        verify(notifier).rfqCancelled(any(RfqEntity.class), eq(waiting));
    }

    @Test
    void quotationIntakeMustCloseBeforeTheRequestedDeliveryDate() {
        CreateRfqRequest request = new CreateRfqRequest("Mooncakes", null, 10, null, LocalDate.now(ZoneOffset.UTC).plusDays(5),
                null, null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(10), null);
        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void closeAndCancelAreIdempotentAndLeaveAcceptedTransactionsUntouched() {
        when(actor.customerId()).thenReturn(17L);
        RfqEntity rfq = entity(RfqStatus.OPEN);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        assertThat(service.close(91L).status()).isEqualTo(RfqStatus.CLOSED);
        assertThat(service.close(91L).status()).isEqualTo(RfqStatus.CLOSED);
        assertThat(service.cancel(91L).status()).isEqualTo(RfqStatus.CANCELLED);
        assertThat(service.cancel(91L).status()).isEqualTo(RfqStatus.CANCELLED);
        assertThatThrownBy(() -> service.close(91L)).isInstanceOf(ApiException.class);
        // Cancel only reads quotations to refuse when orders exist; nothing accepted is rewritten.
        verify(quotations, never()).save(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void cannotEditExpiredRfq() {
        when(actor.customerId()).thenReturn(17L);
        RfqEntity rfq = entity(RfqStatus.OPEN);
        rfq.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        assertThatThrownBy(() -> service.update(91L, Map.of("quantity", 200)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_NOT_OPEN");
    }

    private CreateRfqRequest request(int quantity, BigDecimal budget) {
        return request(quantity, budget, null);
    }

    private CreateRfqRequest request(int quantity, BigDecimal budget, List<Long> targetStoreIds) {
        return new CreateRfqRequest("  Mooncakes for company  ", "Public product requirements", quantity,
                budget, LocalDate.now(ZoneOffset.UTC).plusDays(15), "Private company address",
                "Private design requirements", OffsetDateTime.now(ZoneOffset.UTC).plusDays(7), targetStoreIds);
    }

    private StoreSummaryResponse store(Long storeId, Long supplierId, String name, String status) {
        return new StoreSummaryResponse(storeId, supplierId, "STORE-" + storeId, name, "store-" + storeId, null, status);
    }

    private RfqEntity entity(RfqStatus status) {
        RfqEntity rfq = new RfqEntity();
        ReflectionTestUtils.setField(rfq, "id", 91L);
        rfq.setRfqCode("RFQ-TEST");
        rfq.setCustomerIdRef(17L);
        rfq.setTitle("Mooncakes for company");
        rfq.setDescription("Public product requirements");
        rfq.setQuantity(100);
        rfq.setBudgetAmount(new BigDecimal("3500000.00"));
        rfq.setDeliveryLocation("Private company address");
        rfq.setCustomizationRequirements("Private design requirements");
        rfq.setStatus(status);
        rfq.setCreatedAt(LocalDateTime.of(2026, 10, 14, 0, 0));
        return rfq;
    }
}
