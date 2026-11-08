package mss.mooncake.identity.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mss.mooncake.identity.entity.CustomerProfileEntity;
import mss.mooncake.identity.entity.SupplierProfileEntity;
import mss.mooncake.identity.entity.UserEntity;
import mss.mooncake.identity.enums.UserStatus;
import mss.mooncake.identity.repository.CustomerProfileRepository;
import mss.mooncake.identity.repository.SupplierProfileRepository;
import mss.mooncake.identity.repository.UserRepository;
import mss.mooncake.identity.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** In-app notifications: internal create (service token, dedup) and the user's own inbox. */
@SpringBootTest
@ActiveProfiles("test")
class NotificationControllerIntegrationTest {
    private static final String RFQ_TOKEN = "test-rfq-identity-token-32-bytes-minimum";
    private static final String COMMERCE_TOKEN = "test-commerce-identity-token-32-bytes-minimum";
    private static final String CATALOG_TOKEN = "test-catalog-identity-token-32-bytes-minimum";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired CustomerProfileRepository customers;
    @Autowired SupplierProfileRepository suppliers;
    @MockitoBean JavaMailSender mailSender;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mockMvc;
    private UserEntity buyer;
    private UserEntity other;
    private CustomerProfileEntity buyerProfile;
    private SupplierProfileEntity supplierProfile;
    private UserEntity supplierUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        buyer = newUser();
        other = newUser();
        supplierUser = newUser();
        buyerProfile = customers.save(new CustomerProfileEntity(buyer.getId(), "Người mua", null, now, now));
        supplierProfile = suppliers.save(new SupplierProfileEntity(supplierUser.getId(), "Xưởng bánh",
                "TAX-" + UUID.randomUUID(), "VERIFIED", now, now));
    }

    private UserEntity newUser() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return users.save(new UserEntity("notify-" + UUID.randomUUID() + "@example.com", null, UserStatus.ACTIVE, now, now));
    }

    private static UserPrincipal principal(UserEntity user) {
        return UserPrincipal.create(user.getId(), null, user.getEmail(), List.of("ROLE_CUSTOMER"));
    }

    private Map<String, Object> payload(String recipientField, Long recipientId, String dedupKey) {
        Map<String, Object> body = new HashMap<>();
        body.put(recipientField, recipientId);
        body.put("type", "ORDER_CANCELLED");
        body.put("title", "Đơn hàng đã bị hủy");
        body.put("body", "Đơn #501 đã bị hủy.");
        body.put("target", Map.of("kind", "ORDER", "id", "501"));
        if (dedupKey != null) body.put("dedupKey", dedupKey);
        return body;
    }

    private MockHttpServletRequestBuilder internalPost(String service, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post("/internal/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (service != null) request.header("X-Service-Name", service);
        if (token != null) request.header("X-Service-Token", token);
        return request;
    }

    private ResultActions createFor(UserEntity user, String title) throws Exception {
        Map<String, Object> body = payload("userId", user.getId(), null);
        body.put("title", title);
        return mockMvc.perform(internalPost("rfq", RFQ_TOKEN, body)).andExpect(status().isCreated());
    }

    private long idOf(ResultActions result) throws Exception {
        Map<?, ?> map = json.readValue(result.andReturn().getResponse().getContentAsString(), Map.class);
        return ((Number) map.get("notificationId")).longValue();
    }

    @Test
    void internalCreateRequiresAnAllowedServiceToken() throws Exception {
        Map<String, Object> body = payload("userId", buyer.getId(), null);
        mockMvc.perform(internalPost(null, null, body)).andExpect(status().isUnauthorized());
        mockMvc.perform(internalPost("rfq", "wrong-token-but-long-enough-32-bytes-x", body)).andExpect(status().isUnauthorized());
        mockMvc.perform(internalPost("catalog", CATALOG_TOKEN, body)).andExpect(status().isForbidden());
        mockMvc.perform(internalPost(null, null, body).with(user(principal(buyer)))).andExpect(status().isForbidden());

        mockMvc.perform(internalPost("commerce", COMMERCE_TOKEN, body)).andExpect(status().isCreated());
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, body)).andExpect(status().isCreated());
    }

    @Test
    void resolvesCustomerAndSupplierRecipientsToTheirUsers() throws Exception {
        mockMvc.perform(internalPost("commerce", COMMERCE_TOKEN, payload("customerId", buyerProfile.getId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notificationId").isNumber())
                .andExpect(jsonPath("$.userId").value(buyer.getId()))
                .andExpect(jsonPath("$.created").value(true));
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("supplierId", supplierProfile.getId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(supplierUser.getId()));

        mockMvc.perform(get("/api/notifications").with(user(principal(buyer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type").value("ORDER_CANCELLED"))
                .andExpect(jsonPath("$.items[0].title").value("Đơn hàng đã bị hủy"))
                .andExpect(jsonPath("$.items[0].body").value("Đơn #501 đã bị hủy."))
                .andExpect(jsonPath("$.items[0].target.kind").value("ORDER"))
                .andExpect(jsonPath("$.items[0].target.id").value("501"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/notifications").with(user(principal(supplierUser))))
                .andExpect(jsonPath("$.items", hasSize(1)));
        verifyNoInteractions(mailSender);
    }

    @Test
    void sameDedupKeyForTheSameUserIsCreatedOnce() throws Exception {
        long first = idOf(mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("customerId", buyerProfile.getId(), "ORDER_CANCELLED:501")))
                .andExpect(status().isCreated()));
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("userId", buyer.getId(), "ORDER_CANCELLED:501")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(first))
                .andExpect(jsonPath("$.created").value(false));
        // The key is per recipient: another user gets their own notification.
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("userId", other.getId(), "ORDER_CANCELLED:501")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications/unread-count").with(user(principal(buyer))))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void rejectsInvalidRequestsAndUnknownRecipients() throws Exception {
        Map<String, Object> none = payload("userId", buyer.getId(), null);
        none.remove("userId");
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, none)).andExpect(status().isBadRequest());

        Map<String, Object> two = payload("userId", buyer.getId(), null);
        two.put("customerId", buyerProfile.getId());
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, two)).andExpect(status().isBadRequest());

        Map<String, Object> noTitle = payload("userId", buyer.getId(), null);
        noTitle.put("title", " ");
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, noTitle)).andExpect(status().isBadRequest());

        Map<String, Object> badType = payload("userId", buyer.getId(), null);
        badType.put("type", "order cancelled");
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, badType)).andExpect(status().isBadRequest());

        Map<String, Object> noKind = payload("userId", buyer.getId(), null);
        noKind.put("target", Map.of("id", "501"));
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, noKind)).andExpect(status().isBadRequest());

        Map<String, Object> nestedTarget = payload("userId", buyer.getId(), null);
        nestedTarget.put("target", Map.of("kind", "ORDER", "id", Map.of("nested", 1)));
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, nestedTarget)).andExpect(status().isBadRequest());

        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("customerId", 987654321L, null))).andExpect(status().isNotFound());
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("supplierId", 987654321L, null))).andExpect(status().isNotFound());
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, payload("userId", 987654321L, null))).andExpect(status().isNotFound());

        Map<String, Object> noTarget = payload("userId", buyer.getId(), null);
        noTarget.remove("target");
        noTarget.remove("body");
        mockMvc.perform(internalPost("rfq", RFQ_TOKEN, noTarget)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/notifications").with(user(principal(buyer))))
                .andExpect(jsonPath("$.items[0].target").value(nullValue()))
                .andExpect(jsonPath("$.items[0].body").value(nullValue()));
    }

    @Test
    void usersReadAndMarkOnlyTheirOwnNotifications() throws Exception {
        long first = idOf(createFor(buyer, "Một"));
        createFor(buyer, "Hai");
        createFor(buyer, "Ba");
        createFor(other, "Của người khác");

        mockMvc.perform(get("/api/notifications/unread-count").with(user(principal(buyer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(3));

        // Another user's notification looks missing rather than forbidden.
        mockMvc.perform(post("/api/notifications/" + first + "/read").with(user(principal(other))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/notifications/" + first + "/read").with(user(principal(buyer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(first))
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isString());
        String readAt = json.readValue(mockMvc.perform(post("/api/notifications/" + first + "/read").with(user(principal(buyer))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), Map.class).get("readAt").toString();
        assertThat(readAt).isNotBlank();

        mockMvc.perform(get("/api/notifications/unread-count").with(user(principal(buyer))))
                .andExpect(jsonPath("$.unreadCount").value(2));
        mockMvc.perform(post("/api/notifications/read-all").with(user(principal(buyer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2));
        mockMvc.perform(get("/api/notifications/unread-count").with(user(principal(buyer))))
                .andExpect(jsonPath("$.unreadCount").value(0));
        // Marking everything read never touches other users.
        mockMvc.perform(get("/api/notifications/unread-count").with(user(principal(other))))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void listsNewestFirstWithPaging() throws Exception {
        createFor(buyer, "Cũ nhất");
        createFor(buyer, "Giữa");
        createFor(buyer, "Mới nhất");

        mockMvc.perform(get("/api/notifications?page=0&size=2").with(user(principal(buyer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].title").value("Mới nhất"))
                .andExpect(jsonPath("$.items[1].title").value("Giữa"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/notifications?page=1&size=2").with(user(principal(buyer))))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("Cũ nhất"));

        mockMvc.perform(get("/api/notifications?size=0").with(user(principal(buyer)))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/notifications?size=51").with(user(principal(buyer)))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/notifications?page=-1").with(user(principal(buyer)))).andExpect(status().isBadRequest());
    }

    @Test
    void inboxRequiresALoggedInUser() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        // A service token is not a user session.
        mockMvc.perform(get("/api/notifications").header("X-Service-Name", "rfq").header("X-Service-Token", RFQ_TOKEN))
                .andExpect(status().isForbidden());
    }
}
