package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
import java.util.Optional;
import mss.mooncake.rfqnegotiation.dto.request.CreateRfqRequest;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.impl.RfqApplicationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RfqApplicationServiceTest {
    @Mock private RfqRepository rfqs;
    @Mock private QuotationRepository quotations;
    @Mock private ActorContext actor;
    private ValidatorFactory validators;
    private RfqApplicationService service;

    @BeforeEach
    void setUp() {
        validators = Validation.buildDefaultValidatorFactory();
        service = new RfqApplicationServiceImpl(rfqs, quotations, actor, validators.getValidator());
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
    }

    @Test
    void publicDetailOmitsCustomerAndPrivateDeliveryAndCustomizationData() {
        when(rfqs.findById(91L)).thenReturn(Optional.of(entity(RfqStatus.OPEN)));
        when(actor.optionalCustomerId()).thenReturn(null);
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
    void closeAndCancelAreIdempotentAndLeaveAcceptedTransactionsUntouched() {
        when(actor.customerId()).thenReturn(17L);
        RfqEntity rfq = entity(RfqStatus.OPEN);
        when(rfqs.findByIdForUpdate(91L)).thenReturn(Optional.of(rfq));
        assertThat(service.close(91L).status()).isEqualTo(RfqStatus.CLOSED);
        assertThat(service.close(91L).status()).isEqualTo(RfqStatus.CLOSED);
        assertThat(service.cancel(91L).status()).isEqualTo(RfqStatus.CANCELLED);
        assertThat(service.cancel(91L).status()).isEqualTo(RfqStatus.CANCELLED);
        assertThatThrownBy(() -> service.close(91L)).isInstanceOf(ApiException.class);
        verifyNoInteractions(quotations);
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
        return new CreateRfqRequest("  Mooncakes for company  ", "Public product requirements", quantity,
                budget, LocalDate.now(ZoneOffset.UTC).plusDays(15), "Private company address",
                "Private design requirements", OffsetDateTime.now(ZoneOffset.UTC).plusDays(7));
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
