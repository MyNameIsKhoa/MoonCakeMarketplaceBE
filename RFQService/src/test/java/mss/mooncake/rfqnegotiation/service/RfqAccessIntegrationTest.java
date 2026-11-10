package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import feign.FeignException;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.CommerceServiceClient;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.UserSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationItemRequest;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.entity.RfqTargetStoreEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.repository.*;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "app.conversion-recovery.enabled=false")
class RfqAccessIntegrationTest {
    @Autowired RfqApplicationService service;
    @Autowired QuotationManagementService quotationService;
    @Autowired RfqRepository rfqs;
    @Autowired QuotationConversionRepository conversions;
    @Autowired QuotationItemRepository items;
    @Autowired QuotationRepository quotations;
    @Autowired MessageRepository messages;
    @Autowired ConversationRepository conversationRepository;
    @Autowired RfqTargetStoreRepository targets;
    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy securityFilterChain;
    @MockitoBean IdentityServiceClient identity;
    @MockitoBean CatalogServiceClient catalog;
    @MockitoBean CommerceServiceClient commerce;
    MockMvc mvc;

    @BeforeEach void setup() {
        conversions.deleteAll(); items.deleteAll(); quotations.deleteAll();
        messages.deleteAll(); conversationRepository.deleteAll(); targets.deleteAll(); rfqs.deleteAll();
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilterChain).build();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void selectedStoreRfqsAppearInFeedOnlyForInvitedSuppliers() {
        save("public", RfqStatus.OPEN, null);
        RfqEntity invited = save("for-store-30", RfqStatus.OPEN, null, RfqAudience.SELECTED_STORES);
        targets.save(new RfqTargetStoreEntity(invited.getId(), 30L, 77L, "Bakery"));
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        when(identity.getSupplierByUser(303L)).thenReturn(new SupplierSummaryResponse(88L, 303L, "Other", null, "VERIFIED"));

        assertThatThrownBy(this::titles).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AUTHENTICATION_REQUIRED");
        login(202L);
        assertThat(titles()).containsExactlyInAnyOrder("public", "for-store-30");
        assertThat(service.list(null, false, null, 0, 20, null).items()).filteredOn(r -> r.title().equals("for-store-30"))
                .extracting(RfqResponse::audience).containsExactly(RfqAudience.SELECTED_STORES);
        login(303L);
        assertThat(titles()).containsExactly("public");
    }

    private List<String> titles() {
        return service.list(null, false, null, 0, 20, null).items().stream().map(RfqResponse::title).toList();
    }

    private void login(Long userId) {
        var principal = new UserPrincipal(userId, "user@test", List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test void publicFeedHidesExpiredOpenRfqsButKeepsClosedOnes() {
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        login(202L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        save("open-no-expiry", RfqStatus.OPEN, null);
        save("open-future", RfqStatus.OPEN, now.plusDays(1));
        save("open-expired", RfqStatus.OPEN, now.minusMinutes(1));
        save("closed", RfqStatus.CLOSED, now.minusDays(1));
        save("cancelled", RfqStatus.CANCELLED, null);

        assertThat(service.list(null, false, null, 0, 20, null).items()).extracting(RfqResponse::title)
                .containsExactlyInAnyOrder("open-no-expiry", "open-future", "closed");
        assertThat(service.list(null, false, RfqStatus.OPEN, 0, 20, null).items()).extracting(RfqResponse::title)
                .containsExactlyInAnyOrder("open-no-expiry", "open-future");
    }

    @Test void httpAccessRequiresAuthenticationAndTheMatchingB2bProfile() throws Exception {
        RfqEntity rfq = save("public", RfqStatus.OPEN, null);
        customer(101L, 11L);
        noSupplier(303L);
        when(identity.getUserSummary(202L)).thenReturn(new UserSummaryResponse(202L, null, "Supplier", "supplier@test", null));
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        for (String path : List.of("/api/rfqs", "/api/rfqs?mine=true", "/api/rfqs/" + rfq.getId())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/rfqs").header("Authorization", token(101L, "ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/rfqs").header("Authorization", token(303L, "ROLE_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/rfqs?mine=true").header("Authorization", token(202L, "ROLE_SUPPLIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/rfqs").header("Authorization", token(202L, "ROLE_SUPPLIER"))).andExpect(status().isOk());
        mvc.perform(get("/api/rfqs?mine=true&customerId=999").header("Authorization", token(101L, "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].customerId").value("11"));
    }

    @Test void customerReadsOnlyOwnedDetailsWhileSupplierGetsRedactedPublishedDetails() throws Exception {
        RfqEntity rfq = save("private-fields", RfqStatus.OPEN, null);
        rfq.setDeliveryLocation("Private address"); rfq.setCustomizationRequirements("Private design"); rfqs.save(rfq);
        customer(101L, 11L);
        customer(303L, 12L);
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        String path = "/api/rfqs/" + rfq.getId();
        mvc.perform(get(path).header("Authorization", token(101L, "ROLE_CUSTOMER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("11")).andExpect(jsonPath("$.deliveryLocation").value("Private address"));
        mvc.perform(get(path).header("Authorization", token(303L, "ROLE_CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", token(202L, "ROLE_SUPPLIER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").doesNotExist()).andExpect(jsonPath("$.deliveryLocation").doesNotExist())
                .andExpect(jsonPath("$.customizationRequirements").doesNotExist());
    }

    @Test void pendingSupplierCanReadButCannotQuote() throws Exception {
        RfqEntity rfq = save("public", RfqStatus.OPEN, null);
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "PENDING"));
        mvc.perform(get("/api/rfqs").header("Authorization", token(202L, "ROLE_SUPPLIER"))).andExpect(status().isOk());
        mvc.perform(get("/api/rfqs/" + rfq.getId()).header("Authorization", token(202L, "ROLE_SUPPLIER"))).andExpect(status().isOk());
        login(202L);
        var request = new CreateQuotationRequest(30L, BigDecimal.ZERO, java.time.OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), 1,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(7).toLocalDate(), null, BigDecimal.ZERO, null,
                List.of(new QuotationItemRequest(null, null, "Custom", null, 1, BigDecimal.TEN, BigDecimal.ZERO, null)));
        assertThatThrownBy(() -> quotationService.create(rfq.getId(), "pending", request)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("SUPPLIER_NOT_VERIFIED");
    }

    @Test void supplierModeWinsForDualProfilesIncludingQuotationPrivacy() throws Exception {
        RfqEntity rfq = save("dual-owner", RfqStatus.OPEN, null);
        when(identity.getUserSummary(202L)).thenReturn(new UserSummaryResponse(202L, 11L, "Dual", "dual@test", null));
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        mvc.perform(get("/api/rfqs?mine=true").header("Authorization", token(202L, "ROLE_CUSTOMER", "ROLE_SUPPLIER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CUSTOMER_MODE_REQUIRED"));
        mvc.perform(get("/api/rfqs/" + rfq.getId()).header("Authorization", token(202L, "ROLE_CUSTOMER", "ROLE_SUPPLIER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerId").doesNotExist());
        QuotationEntity own = quote(rfq.getId(), 77L, 30L);
        QuotationEntity other = quote(rfq.getId(), 88L, 31L);
        login(202L);
        assertThat(quotationService.listForRfq(rfq.getId(), 0, 20, null).items())
                .extracting(q -> q.quotationId()).containsExactly(own.getId().toString());
        assertThatThrownBy(() -> quotationService.get(other.getId())).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_RESOURCE_OWNER");
    }

    @Test void supplierLookupFailureDoesNotFallBackToPublicAccess() {
        save("public", RfqStatus.OPEN, null);
        login(202L);
        when(identity.getSupplierByUser(202L)).thenThrow(feignError(503));
        assertThatThrownBy(this::titles).isInstanceOf(ApiException.class).extracting("code").isEqualTo("IDENTITY_UNAVAILABLE");
    }

    private void customer(Long userId, Long customerId) {
        noSupplier(userId);
        when(identity.getUserSummary(userId)).thenReturn(new UserSummaryResponse(userId, customerId, "Buyer", "buyer@test", null));
    }

    private void noSupplier(Long userId) {
        when(identity.getSupplierByUser(userId)).thenThrow(feignError(404));
    }

    private FeignException feignError(int status) {
        var request = feign.Request.create(feign.Request.HttpMethod.GET, "http://identity/internal", Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("IdentityServiceClient", feign.Response.builder().status(status).request(request).headers(Map.of()).build());
    }

    private String token(Long userId, String... roles) {
        return "Bearer " + Jwts.builder().setSubject("test@test").claim("userId", userId).claim("roles", List.of(roles))
                .setIssuer("mooncake-identity").setAudience("mooncake-api").setExpiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor("MoonCakeMarketplaceTestSecretKeyMustBeAtLeast256BitsLong!".getBytes(StandardCharsets.UTF_8))).compact();
    }

    private QuotationEntity quote(Long rfqId, Long supplierId, Long storeId) {
        var q = new QuotationEntity(); q.setRfqId(rfqId); q.setSupplierIdRef(supplierId); q.setStoreIdRef(storeId);
        q.setVersionNo(1); q.setStatus(QuotationStatus.SUBMITTED); q.setTotalAmount(BigDecimal.TEN);
        q.setDepositPercent(BigDecimal.ZERO); q.setShippingFee(BigDecimal.ZERO);
        q.setValidUntil(LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        q.setProposedDeliveryDate(LocalDateTime.now(ZoneOffset.UTC).plusDays(7).toLocalDate());
        return quotations.save(q);
    }

    private RfqEntity save(String title, RfqStatus status, LocalDateTime expiresAt) {
        return save(title, status, expiresAt, RfqAudience.ALL);
    }

    private RfqEntity save(String title, RfqStatus status, LocalDateTime expiresAt, RfqAudience audience) {
        var rfq = new RfqEntity();
        rfq.setRfqCode("FEED-" + title);
        rfq.setCustomerIdRef(11L);
        rfq.setTitle(title);
        rfq.setQuantity(10);
        rfq.setStatus(status);
        rfq.setExpiresAt(expiresAt);
        rfq.setAudience(audience);
        rfq.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return rfqs.save(rfq);
    }
}
