package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.catalog.ProductSnapshotResponse;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationDecisionRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationItemRequest;
import mss.mooncake.rfqnegotiation.dto.request.ReviseQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationResponse;
import mss.mooncake.rfqnegotiation.entity.QuotationCommandEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationItemEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.notification.RfqNotifier;
import mss.mooncake.rfqnegotiation.repository.QuotationCommandRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationConversionRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationItemRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.repository.RfqTargetStoreRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.impl.QuotationManagementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class QuotationManagementServiceTest {
    @Mock private RfqRepository rfqs;
    @Mock private QuotationRepository quotations;
    @Mock private QuotationItemRepository items;
    @Mock private QuotationCommandRepository commands;
    @Mock private QuotationConversionRepository conversions;
    @Mock private RfqTargetStoreRepository targets;
    @Mock private CatalogServiceClient catalog;
    @Mock private ActorContext actor;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private RfqNotifier notifier;
    private QuotationManagementService service;
    private RfqEntity rfq;
    private final OffsetDateTime validity = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    private final LocalDate deliveryDate = LocalDate.now(ZoneOffset.UTC).plusDays(7);
    private final LocalDate todayInVietnam = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));

    @BeforeEach
    void setUp() {
        service = new QuotationManagementServiceImpl(rfqs, quotations, items, commands, conversions,
                targets, catalog, actor, transactionManager, notifier);
        rfq = new RfqEntity();
        rfq.setStatus(RfqStatus.OPEN);
        rfq.setCustomerIdRef(2L);
        rfq.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        lenient().when(actor.userId()).thenReturn(10L);
        lenient().when(actor.supplierId()).thenReturn(20L);
        lenient().when(actor.verifiedSupplierId()).thenReturn(20L);
        lenient().when(catalog.getStoreSummary(30L)).thenReturn(activeStore(20L));
        lenient().when(rfqs.findByIdForUpdate(1L)).thenReturn(Optional.of(rfq));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(quotations.saveAndFlush(any())).thenAnswer(invocation -> {
            QuotationEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });
        lenient().when(items.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void suspendedSupplierCannotCreateAQuotation() {
        when(actor.verifiedSupplierId()).thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "SUPPLIER_NOT_VERIFIED", "Hồ sơ nhà cung cấp chưa được xác minh hoặc đang bị khóa"));
        assertCode(() -> service.create(1L, "suspended", request(customItem("120000.00", "0"))), "SUPPLIER_NOT_VERIFIED");
    }

    @Test
    void calculatesNegotiatedTotalsAndCreatesSubmittedVersion() {
        QuotationResponse result = service.create(1L, "create-1", request(customItem("120000.00", "15000.00")));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(QuotationStatus.SUBMITTED);
        assertThat(result.totalAmount()).isEqualTo("235000.00");
        assertThat(result.items().getFirst().subtotal()).isEqualTo("225000.00");
        assertThat(result.items().getFirst().imageUrlSnapshot()).isEqualTo("/images/placeholders/custom-order.svg");
        assertThat(result.submittedAt()).endsWith("Z");
        verify(commands).save(any(QuotationCommandEntity.class));
        verify(transactionManager).commit(any());
        verify(notifier).quotationSubmitted(eq(rfq), argThat(q -> q.getPreviousQuotationId() == null), eq("Cửa hàng bánh"));
    }

    @Test
    void uninvitedSupplierCannotSeeOrQuoteSelectedStoreRfq() {
        rfq.setAudience(RfqAudience.SELECTED_STORES);
        when(targets.existsByRfqIdAndSupplierIdRef(1L, 20L)).thenReturn(false);

        assertCode(() -> service.create(1L, "create-1", request(customItem("100.00", "0"))), "RFQ_NOT_FOUND");
        verify(quotations, never()).saveAndFlush(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void invitedSupplierMustQuoteFromAnInvitedStore() {
        rfq.setAudience(RfqAudience.SELECTED_STORES);
        when(targets.existsByRfqIdAndSupplierIdRef(1L, 20L)).thenReturn(true);
        when(targets.existsByRfqIdAndStoreIdRef(1L, 30L)).thenReturn(false, true);

        assertCode(() -> service.create(1L, "create-1", request(customItem("100.00", "0"))), "STORE_NOT_TARGETED");
        verify(quotations, never()).saveAndFlush(any());
        assertThat(service.create(1L, "create-2", request(customItem("100.00", "0"))).status())
                .isEqualTo(QuotationStatus.SUBMITTED);
    }

    @Test
    void uninvitedSupplierCannotListQuotationsOfSelectedStoreRfq() {
        rfq.setAudience(RfqAudience.SELECTED_STORES);
        when(rfqs.findById(1L)).thenReturn(Optional.of(rfq));
        when(targets.existsByRfqIdAndSupplierIdRef(1L, 20L)).thenReturn(false);

        assertCode(() -> service.listForRfq(1L, 0, 20, null), "RFQ_NOT_FOUND");
    }

    @Test
    void rejectsStoreOwnedByAnotherSupplier() {
        when(catalog.getStoreSummary(30L)).thenReturn(activeStore(999L));

        assertCode(() -> service.create(1L, "create-1", request(customItem("100.00", "0"))), "STORE_NOT_OWNED");
        verify(rfqs, never()).findByIdForUpdate(any());
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void rejectsExpiredRfqBeforePersistingQuotation() {
        rfq.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

        assertCode(() -> service.create(1L, "create-1", request(customItem("100.00", "0"))), "RFQ_EXPIRED");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDiscountAboveLineGross() {
        assertCode(() -> service.create(1L, "create-1", request(customItem("100.00", "201"))),
                "DISCOUNT_EXCEEDS_LINE_AMOUNT");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void rejectsMonetaryOverflowAndImplicitRounding() {
        assertCode(() -> service.create(1L, "overflow", request(customItem("9999999999999999", "0"))), "INVALID_AMOUNT");
        assertCode(() -> service.create(1L, "precision", request(customItem("100.001", "0"))), "INVALID_AMOUNT_SCALE");
    }

    @Test
    void rejectsFractionalDongBecausePaymentsAreWholeVnd() {
        assertCode(() -> service.create(1L, "price", request(customItem("100.50", "0"))), "AMOUNT_NOT_WHOLE_VND");
        assertCode(() -> service.create(1L, "discount", request(customItem("100.00", "0.50"))), "AMOUNT_NOT_WHOLE_VND");
        assertCode(() -> service.create(1L, "shipping", new CreateQuotationRequest(30L, new BigDecimal("30.00"), validity, 3,
                deliveryDate, "EXPRESS", new BigDecimal("10000.50"), "Chuyển khoản", List.of(customItem("100.00", "0")))),
                "AMOUNT_NOT_WHOLE_VND");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void usesCatalogSnapshotForVariantAndKeepsNegotiatedPrice() {
        when(catalog.getProductSnapshot(40L, 50L)).thenReturn(new ProductSnapshotResponse(
                40L, 50L, "Bánh trung thu", "Đậu xanh", "CAT-001", new BigDecimal("999999.00"),
                30L, 20L, "P-001", "/images/catalog/variant-50.jpg", "ACTIVE"));
        QuotationItemRequest line = new QuotationItemRequest(40L, 50L, "Tên giả", "SKU giả", 2,
                new BigDecimal("100.00"), BigDecimal.ZERO, null);

        QuotationResponse result = service.create(1L, "variant", request(line));

        assertThat(result.items().getFirst().itemNameSnapshot()).isEqualTo("Bánh trung thu - Đậu xanh");
        assertThat(result.items().getFirst().skuSnapshot()).isEqualTo("CAT-001");
        assertThat(result.items().getFirst().unitPrice()).isEqualTo("100.00");
        assertThat(result.items().getFirst().imageUrlSnapshot()).isEqualTo("/images/catalog/variant-50.jpg");
    }

    @Test
    void rejectsCatalogImageThatCannotFitTheOrderSnapshot() {
        when(catalog.getProductSnapshot(40L, 50L)).thenReturn(new ProductSnapshotResponse(
                40L, 50L, "Bánh", "Đậu xanh", "SKU", BigDecimal.TEN, 30L, 20L, null, "x".repeat(501), "ACTIVE"));
        QuotationItemRequest line = new QuotationItemRequest(40L, 50L, null, null, 2, BigDecimal.TEN, BigDecimal.ZERO, null);
        assertCode(() -> service.create(1L, "long-image", request(line)), "CATALOG_INVALID_RESPONSE");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void rejectsVariantFromDifferentStore() {
        when(catalog.getProductSnapshot(40L, 50L)).thenReturn(new ProductSnapshotResponse(
                40L, 50L, "Bánh", "Đậu xanh", "SKU", BigDecimal.TEN, 999L, 20L, null, null, "ACTIVE"));
        QuotationItemRequest line = new QuotationItemRequest(40L, 50L, null, null, 2, BigDecimal.TEN, BigDecimal.ZERO, null);

        assertCode(() -> service.create(1L, "variant", request(line)), "VARIANT_STORE_MISMATCH");
    }

    @Test
    void requiresRevisionWhenStoreAlreadyHasSubmittedQuotation() {
        QuotationEntity previous = previousQuotation();
        when(quotations.findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(1L, 30L)).thenReturn(Optional.of(previous));

        assertCode(() -> service.create(1L, "create-2", request(customItem("100.00", "0"))), "QUOTATION_REVISION_REQUIRED");
    }

    @Test
    void createsImmutableRevisionLinkedToPreviousVersion() {
        QuotationEntity previous = prepareRevision();
        BigDecimal previousAmount = previous.getTotalAmount();
        ReviseQuotationRequest request = revision(3);

        QuotationResponse result = service.revise(90L, "revision-1", request);

        assertThat(result.version()).isEqualTo(4);
        assertThat(result.previousQuotationId()).isEqualTo("90");
        assertThat(result.status()).isEqualTo(QuotationStatus.SUBMITTED);
        assertThat(previous.getStatus()).isEqualTo(QuotationStatus.REVISED);
        assertThat(previous.getTotalAmount()).isEqualByComparingTo(previousAmount);
        assertThat(previous.getVersionNo()).isEqualTo(3);
        verify(notifier).quotationSubmitted(eq(rfq), argThat(q -> Long.valueOf(90L).equals(q.getPreviousQuotationId())),
                eq("Cửa hàng bánh"));
    }

    @Test
    void rejectsStaleRevisionVersion() {
        prepareRevision();

        assertCode(() -> service.revise(90L, "revision-1", revision(2)), "QUOTATION_VERSION_CONFLICT");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void cannotReviseAfterConversionBegins() {
        QuotationEntity previous = prepareRevision();
        when(conversions.existsByQuotationIdAndStatusNot(90L, "FAILED")).thenReturn(true);

        assertCode(() -> service.revise(90L, "revision-1", revision(3)), "QUOTATION_CONVERSION_STARTED");
        assertThat(previous.getStatus()).isEqualTo(QuotationStatus.SUBMITTED);
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void replayReturnsSameQuotationWithoutCatalogRequest() {
        CreateQuotationRequest request = request(customItem("100.00", "0"));
        service.create(1L, "create-1", request);
        ArgumentCaptor<QuotationCommandEntity> commandCaptor = ArgumentCaptor.forClass(QuotationCommandEntity.class);
        verify(commands).save(commandCaptor.capture());
        when(commands.findByActorUserIdAndCommandTypeAndTargetIdAndRequestKey(10L, "CREATE", 1L, "create-1"))
                .thenReturn(Optional.of(commandCaptor.getValue()));
        ArgumentCaptor<QuotationEntity> savedCaptor = ArgumentCaptor.forClass(QuotationEntity.class);
        verify(quotations).saveAndFlush(savedCaptor.capture());
        when(quotations.findById(100L)).thenReturn(Optional.of(savedCaptor.getValue()));
        when(items.findByQuotationIdOrderByIdAsc(100L)).thenReturn(List.of());

        QuotationResponse replay = service.create(1L, "create-1", request);

        assertThat(replay.quotationId()).isEqualTo("100");
        verify(catalog).getStoreSummary(30L);
        verify(quotations).saveAndFlush(any());
        // A replayed request is the same quotation: the buyer is not told twice.
        verify(notifier, times(1)).quotationSubmitted(any(), any(), any());
    }

    @Test
    void rejectsIdempotencyKeyWithDifferentPayload() {
        when(commands.findByActorUserIdAndCommandTypeAndTargetIdAndRequestKey(10L, "CREATE", 1L, "used-key"))
                .thenReturn(Optional.of(new QuotationCommandEntity(10L, "CREATE", 1L, "used-key", "different-hash", 100L)));

        assertCode(() -> service.create(1L, "used-key", request(customItem("100.00", "0"))), "IDEMPOTENCY_KEY_CONFLICT");
        verify(catalog, never()).getStoreSummary(any());
    }

    @Test
    void supplierCannotReadCompetitorQuotation() {
        QuotationEntity quotation = previousQuotation();
        when(quotations.findById(90L)).thenReturn(Optional.of(quotation));
        when(rfqs.findById(1L)).thenReturn(Optional.of(rfq));
        doThrow(ApiException.forbidden("NOT_RESOURCE_OWNER", "Không sở hữu báo giá"))
                .when(actor).assertSupplierOwner(20L);

        assertCode(() -> service.get(90L), "NOT_RESOURCE_OWNER");
        verify(items, never()).findByQuotationIdOrderByIdAsc(any());
    }

    @Test
    void supplierCannotWithdrawQuotationAfterAcceptance() {
        QuotationEntity quotation = previousQuotation();
        quotation.setStatus(QuotationStatus.ACCEPTED);
        when(quotations.findById(90L)).thenReturn(Optional.of(quotation));
        when(quotations.findByIdForUpdate(90L)).thenReturn(Optional.of(quotation));

        assertCode(() -> service.cancel(90L, new QuotationDecisionRequest("Đổi kế hoạch")), "QUOTATION_NOT_SUBMITTED");
        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void buyerRejectionNotifiesTheSupplierOnce() {
        QuotationEntity quotation = decidableQuotation();
        when(quotations.findById(90L)).thenReturn(Optional.of(quotation));
        when(quotations.findByIdForUpdate(90L)).thenReturn(Optional.of(quotation));
        when(rfqs.findById(1L)).thenReturn(Optional.of(rfq));

        service.reject(90L, new QuotationDecisionRequest("Giá cao hơn ngân sách"));
        service.reject(90L, new QuotationDecisionRequest("Giá cao hơn ngân sách"));

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.REJECTED);
        verify(notifier, times(1)).quotationRejected(rfq, quotation);
    }

    @Test
    void supplierWithdrawingItsOwnQuotationNotifiesNobody() {
        QuotationEntity quotation = decidableQuotation();
        when(quotations.findById(90L)).thenReturn(Optional.of(quotation));
        when(quotations.findByIdForUpdate(90L)).thenReturn(Optional.of(quotation));

        service.cancel(90L, new QuotationDecisionRequest("Hết nguyên liệu"));

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.CANCELLED);
        verifyNoInteractions(notifier);
    }

    @Test
    void depositQuotationNeedsDeliveryAtLeastSixDaysAheadInVietnam() {
        // 48h to pay the deposit, then the remaining balance is due 3 days before delivery.
        assertThatThrownBy(() -> service.create(1L, "create-1", request("30.00", todayInVietnam.plusDays(5))))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("DELIVERY_TOO_SOON_FOR_REMAINING");
                    // The message states the same 6 days as the date it gives.
                    assertThat(exception.getMessage()).contains(dayMonthYear(todayInVietnam.plusDays(6)))
                            .contains("ít nhất 6 ngày").doesNotContain("4 ngày");
                });
        verify(quotations, never()).saveAndFlush(any());

        assertThat(service.create(1L, "create-2", request("30.00", todayInVietnam.plusDays(6))).status())
                .isEqualTo(QuotationStatus.SUBMITTED);
    }

    @Test
    void supplierCannotQuoteOnTheirOwnRfq() {
        when(actor.optionalCustomerId()).thenReturn(2L);
        assertCode(() -> service.create(1L, "self", request(customItem("100.00", "0"))), "SELF_DEALING");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void noNewQuotationOnceTheBuyerAcceptedOne() {
        // Also covers an RFQ still OPEN while that acceptance is pending or predates single-award.
        when(conversions.committedQuantity(1L)).thenReturn(10L);

        assertCode(() -> service.create(1L, "after-award", request(customItem("100.00", "0"))), "RFQ_ALREADY_AWARDED");
        verify(quotations, never()).saveAndFlush(any());
        verifyNoInteractions(notifier);
    }

    @Test
    void noRevisionOnceTheBuyerAcceptedAQuotation() {
        QuotationEntity previous = prepareRevision();
        when(conversions.committedQuantity(1L)).thenReturn(10L);

        assertCode(() -> service.revise(90L, "revision-1", revision(3)), "RFQ_ALREADY_AWARDED");
        assertThat(previous.getStatus()).isEqualTo(QuotationStatus.SUBMITTED);
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void quotationCannotOfferMoreUnitsThanTheRfqAsks() {
        rfq.setQuantity(1);
        assertCode(() -> service.create(1L, "too-many", request(customItem("100.00", "0"))), "QUOTATION_EXCEEDS_RFQ_QUANTITY");
        verify(quotations, never()).saveAndFlush(any());
    }

    @Test
    void fullPaymentQuotationNeedsDeliveryAtLeastThreeDaysAheadInVietnam() {
        // A 0% or 100% deposit is one FULL payment: 48h to pay, and goods are prepared only once it is paid.
        for (String deposit : List.of("0.00", "100.00")) {
            assertThatThrownBy(() -> service.create(1L, "soon-" + deposit, request(deposit, todayInVietnam.plusDays(2))))
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo("DELIVERY_TOO_SOON_FOR_PAYMENT");
                        assertThat(exception.getMessage()).contains(dayMonthYear(todayInVietnam.plusDays(3)))
                                .contains("ít nhất 3 ngày");
                    });
        }
        verify(quotations, never()).saveAndFlush(any());

        assertThat(service.create(1L, "create-0", request("0.00", todayInVietnam.plusDays(3))).status())
                .isEqualTo(QuotationStatus.SUBMITTED);
        assertThat(service.create(1L, "create-100", request("100.00", todayInVietnam.plusDays(3))).status())
                .isEqualTo(QuotationStatus.SUBMITTED);
    }

    @Test
    void depositRevisionNeedsDeliveryAtLeastSixDaysAhead() {
        prepareRevision();

        assertCode(() -> service.revise(90L, "revision-1", new ReviseQuotationRequest(3, new BigDecimal("30.00"),
                validity, 3, todayInVietnam.plusDays(5), "EXPRESS", new BigDecimal("10000.00"), "Chuyển khoản",
                List.of(customItem("100.00", "0")))), "DELIVERY_TOO_SOON_FOR_REMAINING");
        verify(quotations, never()).saveAndFlush(any());
    }

    private static String dayMonthYear(LocalDate day) {
        return day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private QuotationEntity prepareRevision() {
        QuotationEntity previous = previousQuotation();
        when(quotations.findById(90L)).thenReturn(Optional.of(previous));
        when(quotations.findByIdForUpdate(90L)).thenReturn(Optional.of(previous));
        lenient().when(quotations.findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(1L, 30L)).thenReturn(Optional.of(previous));
        return previous;
    }

    private QuotationEntity previousQuotation() {
        QuotationEntity quotation = new QuotationEntity();
        ReflectionTestUtils.setField(quotation, "id", 90L);
        quotation.setRfqId(1L);
        quotation.setSupplierIdRef(20L);
        quotation.setStoreIdRef(30L);
        quotation.setStatus(QuotationStatus.SUBMITTED);
        quotation.setVersionNo(3);
        quotation.setTotalAmount(new BigDecimal("123.45"));
        return quotation;
    }

    /** A SUBMITTED quotation complete enough to be returned after a decision. */
    private QuotationEntity decidableQuotation() {
        QuotationEntity quotation = previousQuotation();
        quotation.setDepositPercent(new BigDecimal("30.00"));
        quotation.setShippingFee(new BigDecimal("10000.00"));
        quotation.setValidUntil(validity.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
        return quotation;
    }

    private ReviseQuotationRequest revision(int expectedVersion) {
        return new ReviseQuotationRequest(expectedVersion, new BigDecimal("30.00"), validity, 3,
                deliveryDate, "EXPRESS", new BigDecimal("10000.00"), "Chuyển khoản",
                List.of(customItem("100.00", "0")));
    }

    private CreateQuotationRequest request(QuotationItemRequest item) {
        return new CreateQuotationRequest(30L, new BigDecimal("30.00"), validity, 3,
                deliveryDate, "EXPRESS", new BigDecimal("10000.00"), "Chuyển khoản", List.of(item));
    }

    private CreateQuotationRequest request(String depositPercent, LocalDate delivery) {
        return new CreateQuotationRequest(30L, new BigDecimal(depositPercent), validity, 3,
                delivery, "EXPRESS", new BigDecimal("10000.00"), "Chuyển khoản", List.of(customItem("100.00", "0")));
    }

    private QuotationItemRequest customItem(String price, String discount) {
        return new QuotationItemRequest(null, null, "Hộp bánh theo thiết kế", null, 2,
                new BigDecimal(price), new BigDecimal(discount), "In logo công ty");
    }

    private StoreSummaryResponse activeStore(Long supplierId) {
        return new StoreSummaryResponse(30L, supplierId, "STORE-30", "Cửa hàng bánh", "banh", null, "ACTIVE");
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
