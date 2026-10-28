package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.CommerceServiceClient;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.entity.RfqTargetStoreEntity;
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

@SpringBootTest(properties = "app.conversion-recovery.enabled=false")
class RfqPublicFeedIntegrationTest {
    @Autowired RfqApplicationService service;
    @Autowired RfqRepository rfqs;
    @Autowired QuotationConversionRepository conversions;
    @Autowired QuotationItemRepository items;
    @Autowired QuotationRepository quotations;
    @Autowired MessageRepository messages;
    @Autowired ConversationRepository conversationRepository;
    @Autowired RfqTargetStoreRepository targets;
    @MockitoBean IdentityServiceClient identity;
    @MockitoBean CatalogServiceClient catalog;
    @MockitoBean CommerceServiceClient commerce;

    @BeforeEach void setup() {
        conversions.deleteAll(); items.deleteAll(); quotations.deleteAll();
        messages.deleteAll(); conversationRepository.deleteAll(); targets.deleteAll(); rfqs.deleteAll();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void selectedStoreRfqsAppearInFeedOnlyForInvitedSuppliers() {
        save("public", RfqStatus.OPEN, null);
        RfqEntity invited = save("for-store-30", RfqStatus.OPEN, null, RfqAudience.SELECTED_STORES);
        targets.save(new RfqTargetStoreEntity(invited.getId(), 30L, 77L, "Bakery"));
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        when(identity.getSupplierByUser(303L)).thenReturn(new SupplierSummaryResponse(88L, 303L, "Other", null, "VERIFIED"));

        assertThat(titles()).containsExactly("public");
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
