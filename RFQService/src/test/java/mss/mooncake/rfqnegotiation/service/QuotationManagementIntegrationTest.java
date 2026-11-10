package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.catalog.ProductSnapshotResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationItemRequest;
import mss.mooncake.rfqnegotiation.dto.request.ReviseQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationResponse;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.repository.QuotationCommandRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationConversionRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationItemRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "app.conversion-recovery.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:quotation_management_test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1"
})
class QuotationManagementIntegrationTest {
    @Autowired private QuotationManagementService service;
    @Autowired private RfqRepository rfqs;
    @Autowired private QuotationRepository quotations;
    @Autowired private QuotationItemRepository items;
    @Autowired private QuotationCommandRepository commands;
    @Autowired private QuotationConversionRepository conversions;
    @MockitoBean private ActorContext actor;
    @MockitoBean private CatalogServiceClient catalog;
    // Quotations notify the buyer through Identity; keep the test off the network.
    @MockitoBean private mss.mooncake.rfqnegotiation.client.IdentityServiceClient identity;
    private Long rfqId;

    @BeforeEach
    void setUp() {
        commands.deleteAll();
        conversions.deleteAll();
        items.deleteAll();
        // Clear revision links before deleting the chain in a database with a self FK.
        quotations.findAll().forEach(q -> { q.setPreviousQuotationId(null); quotations.save(q); });
        quotations.deleteAll();
        rfqs.deleteAll();
        when(actor.userId()).thenReturn(10L);
        when(actor.supplierId()).thenReturn(20L);
        when(actor.verifiedSupplierId()).thenReturn(20L);
        when(catalog.getStoreSummary(30L)).thenReturn(new StoreSummaryResponse(30L, 20L, "STORE-30", "Shop", "shop", null, "ACTIVE"));
        var rfq = new RfqEntity();
        rfq.setRfqCode("RFQ-MANAGEMENT-TEST");
        rfq.setCustomerIdRef(2L);
        rfq.setTitle("Hộp bánh công ty");
        rfq.setQuantity(10);
        rfq.setStatus(RfqStatus.OPEN);
        rfq.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        rfq.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rfqId = rfqs.saveAndFlush(rfq).getId();
    }

    @Test
    void savesImagesPerVersionAndReplaysWithoutRefreshingCatalog() {
        when(catalog.getProductSnapshot(40L, 50L)).thenReturn(snapshot("/images/original.jpg"));
        var base = request();
        var request = new CreateQuotationRequest(base.storeId(), base.depositPercent(), base.validUntil(), base.leadTimeDays(),
                base.proposedDeliveryDate(), base.shippingMethod(), base.shippingFee(), base.paymentTerms(),
                List.of(new QuotationItemRequest(40L, 50L, "Ignored", null, 10, new BigDecimal("100.00"), BigDecimal.ZERO, null)));
        var original = service.create(rfqId, "catalog-original", request);
        Long originalId = Long.valueOf(original.quotationId());
        when(catalog.getProductSnapshot(40L, 50L)).thenReturn(snapshot("/images/revised.jpg"));
        var revision = new ReviseQuotationRequest(1, request.depositPercent(), request.validUntil(), request.leadTimeDays(),
                request.proposedDeliveryDate(), request.shippingMethod(), request.shippingFee(), request.paymentTerms(), request.items());
        var revised = service.revise(originalId, "catalog-revision", revision);

        assertThat(original.items().getFirst().imageUrlSnapshot()).isEqualTo("/images/original.jpg");
        assertThat(revised.items().getFirst().imageUrlSnapshot()).isEqualTo("/images/revised.jpg");
        when(catalog.getProductSnapshot(40L, 50L)).thenThrow(new IllegalStateException("Catalog unavailable"));
        assertThat(service.get(originalId).items().getFirst().imageUrlSnapshot()).isEqualTo("/images/original.jpg");
        assertThat(service.create(rfqId, "catalog-original", request).items().getFirst().imageUrlSnapshot()).isEqualTo("/images/original.jpg");
        assertThat(service.revise(originalId, "catalog-revision", revision).items().getFirst().imageUrlSnapshot()).isEqualTo("/images/revised.jpg");
        assertThat(items.findByQuotationIdOrderByIdAsc(originalId).getFirst().getImageUrlSnapshot()).isEqualTo("/images/original.jpg");
    }

    private ProductSnapshotResponse snapshot(String imageUrl) {
        return new ProductSnapshotResponse(40L, 50L, "Mooncake", "Lotus", "SKU-50", BigDecimal.TEN,
                30L, 20L, "P-40", imageUrl, "ACTIVE");
    }

    @Test
    void concurrentCreateWithSameKeyProducesOneQuotationAndOneCommand() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var first = concurrentCreate(ready, start);
        var second = concurrentCreate(ready, start);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).quotationId()).isEqualTo(second.get(10, TimeUnit.SECONDS).quotationId());
        assertThat(quotations.count()).isEqualTo(1);
        assertThat(commands.count()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(1);
    }

    @Test
    void competingRevisionsOnlyAllowOneSuccessAndPreservePreviousItems() throws Exception {
        QuotationResponse original = service.create(rfqId, "original", request());
        Long originalId = Long.valueOf(original.quotationId());
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var first = concurrentRevision(originalId, "revision-a", ready, start);
        var second = concurrentRevision(originalId, "revision-b", ready, start);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<RevisionOutcome> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertThat(results.stream().filter(result -> result.response() != null)).hasSize(1);
        assertThat(results.stream().filter(result -> result.errorCode() != null)).hasSize(1);
        assertThat(quotations.count()).isEqualTo(2);
        assertThat(commands.count()).isEqualTo(2);
        assertThat(quotations.findById(originalId).orElseThrow().getStatus()).isEqualTo(QuotationStatus.REVISED);
        assertThat(items.findByQuotationIdOrderByIdAsc(originalId).getFirst().getUnitPrice()).isEqualByComparingTo("100.00");
        assertThat(quotations.findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(rfqId, 30L).orElseThrow().getVersionNo()).isEqualTo(2);
    }

    private CompletableFuture<QuotationResponse> concurrentCreate(CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return service.create(rfqId, "same-create-key", request());
        });
    }

    private CompletableFuture<RevisionOutcome> concurrentRevision(Long quotationId, String key,
                                                                 CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            var request = request();
            var revision = new ReviseQuotationRequest(1, request.depositPercent(), request.validUntil(), request.leadTimeDays(),
                    request.proposedDeliveryDate(), request.shippingMethod(), request.shippingFee(), request.paymentTerms(), request.items());
            try {
                return new RevisionOutcome(service.revise(quotationId, key, revision), null);
            } catch (ApiException ex) {
                return new RevisionOutcome(null, ex.getCode());
            }
        });
    }

    private void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private CreateQuotationRequest request() {
        return new CreateQuotationRequest(30L, new BigDecimal("30.00"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0), 3,
                LocalDate.now(ZoneOffset.UTC).plusDays(7), "EXPRESS", new BigDecimal("10.00"), null,
                List.of(new QuotationItemRequest(null, null, "Hộp bánh custom", null, 10,
                        new BigDecimal("100.00"), BigDecimal.ZERO, null)));
    }

    private record RevisionOutcome(QuotationResponse response, String errorCode) {
    }
}
