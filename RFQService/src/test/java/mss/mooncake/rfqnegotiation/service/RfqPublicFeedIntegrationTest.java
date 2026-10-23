package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.CommerceServiceClient;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    @MockitoBean IdentityServiceClient identity;
    @MockitoBean CatalogServiceClient catalog;
    @MockitoBean CommerceServiceClient commerce;

    @BeforeEach void setup() {
        conversions.deleteAll(); items.deleteAll(); quotations.deleteAll();
        messages.deleteAll(); conversationRepository.deleteAll(); rfqs.deleteAll();
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

    private void save(String title, RfqStatus status, LocalDateTime expiresAt) {
        var rfq = new RfqEntity();
        rfq.setRfqCode("FEED-" + title);
        rfq.setCustomerIdRef(11L);
        rfq.setTitle(title);
        rfq.setQuantity(10);
        rfq.setStatus(status);
        rfq.setExpiresAt(expiresAt);
        rfq.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        rfqs.save(rfq);
    }
}
