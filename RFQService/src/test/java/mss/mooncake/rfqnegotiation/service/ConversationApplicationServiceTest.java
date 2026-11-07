package mss.mooncake.rfqnegotiation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.UserSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateConversationRequest;
import mss.mooncake.rfqnegotiation.dto.request.SendMessageRequest;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.entity.RfqTargetStoreEntity;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.repository.ConversationReadStateRepository;
import mss.mooncake.rfqnegotiation.repository.ConversationRepository;
import mss.mooncake.rfqnegotiation.repository.MessageRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.repository.RfqTargetStoreRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.security.UserPrincipal;
import mss.mooncake.rfqnegotiation.service.impl.ConversationApplicationServiceImpl;
import mss.mooncake.rfqnegotiation.dto.response.ConversationResponse;
import mss.mooncake.rfqnegotiation.dto.response.MessageResponse;
import mss.mooncake.rfqnegotiation.websocket.MessagePersistedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = ConversationApplicationServiceTest.TestConfig.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:chat;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false", "spring.cloud.compatibility-verifier.enabled=false"})
class ConversationApplicationServiceTest {
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = RfqEntity.class)
    @EnableJpaRepositories(basePackageClasses = ConversationRepository.class)
    @Import({ConversationApplicationServiceImpl.class, ActorContext.class})
    static class TestConfig {
        @Bean BroadcastProbe broadcastProbe() { return new BroadcastProbe(); }
    }

    static class BroadcastProbe {
        final List<MessageResponse> delivered = new CopyOnWriteArrayList<>();
        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(MessagePersistedEvent event) { delivered.add(event.message()); }
    }

    @Autowired ConversationApplicationService service;
    @Autowired RfqRepository rfqs;
    @Autowired ConversationRepository conversations;
    @Autowired MessageRepository messages;
    @Autowired ConversationReadStateRepository reads;
    @Autowired RfqTargetStoreRepository targets;
    @MockitoBean IdentityServiceClient identity;
    @Autowired BroadcastProbe broadcast;
    @Autowired PlatformTransactionManager transactionManager;
    private Long rfqId;

    @BeforeEach
    void setup() {
        broadcast.delivered.clear();
        reads.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        targets.deleteAll();
        rfqs.deleteAll();
        when(identity.getUserSummary(101L)).thenReturn(new UserSummaryResponse(101L, 11L, "Buyer", "buyer@test", null));
        when(identity.getUserSummary(202L)).thenReturn(new UserSummaryResponse(202L, 22L, "Supplier", "supplier@test", null));
        when(identity.getUserSummary(303L)).thenReturn(new UserSummaryResponse(303L, 33L, "Other", "other@test", null));
        when(identity.getSupplierSummary(77L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        when(identity.getSupplierByUser(202L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "VERIFIED"));
        // Identity answers 404 for accounts without a supplier profile.
        when(identity.getSupplierByUser(101L)).thenThrow(notFound());
        when(identity.getSupplierByUser(303L)).thenThrow(notFound());
        RfqEntity rfq = new RfqEntity();
        rfq.setRfqCode("CHAT-TEST");
        rfq.setCustomerIdRef(11L);
        rfq.setTitle("Mooncakes");
        rfq.setQuantity(10);
        rfq.setStatus(RfqStatus.OPEN);
        rfq.setCreatedAt(LocalDateTime.now());
        rfqId = rfqs.save(rfq).getId();
        login(101L);
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void creationIsIdempotentAndUsesCustomerIdRatherThanUserId() {
        var first = service.create(rfqId, new CreateConversationRequest(77L));
        var retry = service.create(rfqId, new CreateConversationRequest(77L));
        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.conversation().conversationId()).isEqualTo(first.conversation().conversationId());
        assertThat(first.conversation().customerId()).isEqualTo("11");
        assertThat(conversations.count()).isEqualTo(1);
    }

    @Test
    void selectedStoreRfqConversationsAreLimitedToInvitedSuppliers() {
        RfqEntity rfq = rfqs.findById(rfqId).orElseThrow();
        rfq.setAudience(RfqAudience.SELECTED_STORES);
        rfqs.save(rfq);

        assertThatThrownBy(() -> service.create(rfqId, new CreateConversationRequest(77L)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SUPPLIER_NOT_TARGETED");
        login(202L);
        assertThatThrownBy(() -> service.create(rfqId, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("RFQ_NOT_FOUND");
        assertThat(conversations.count()).isZero();

        targets.save(new RfqTargetStoreEntity(rfqId, 30L, 77L, "Bakery"));
        assertThat(service.create(rfqId, null).created()).isTrue();
    }

    @Test
    void unverifiedSupplierCannotOpenNewConversation() {
        when(identity.getSupplierSummary(77L)).thenReturn(new SupplierSummaryResponse(77L, 202L, "Bakery", null, "PENDING"));
        assertThatThrownBy(() -> service.create(rfqId, new CreateConversationRequest(77L)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("SUPPLIER_NOT_VERIFIED");
        assertThat(conversations.count()).isZero();
    }

    @Test
    void outsidersCannotReadHistoryOrSendAndSupplierUserCanParticipate() {
        Long id = createConversation();
        service.assertParticipant(id, 202L);
        assertThatThrownBy(() -> service.assertParticipant(id, 303L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.send(id, 303L, message("outside", "Hi"))).isInstanceOf(ApiException.class);
        login(303L);
        assertThatThrownBy(() -> service.history(id, null, null, 50)).isInstanceOf(ApiException.class);
        assertThat(messages.count()).isZero();
    }

    @Test
    void retryReusesPersistentMessageAndChangedPayloadConflicts() {
        Long id = createConversation();
        var first = service.send(id, 101L, message("client-1", "Hello"));
        var retry = service.send(id, 101L, message("client-1", "Hello"));
        assertThat(retry.messageId()).isEqualTo(first.messageId());
        assertThat(messages.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.send(id, 101L, message("client-1", "Changed"))).isInstanceOf(ApiException.class);
        var supplier = service.send(id, 202L, message("client-1", "Reply"));
        assertThat(supplier.messageId()).isNotEqualTo(first.messageId());
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test
    void cursorsAreExclusiveAndMessagesAreReturnedAscending() {
        Long id = createConversation();
        var a = service.send(id, 101L, message("a", "One"));
        var b = service.send(id, 101L, message("b", "Two"));
        var c = service.send(id, 101L, message("c", "Three"));
        var latest = service.history(id, null, null, 2);
        assertThat(latest.items()).extracting("messageId").containsExactly(b.messageId(), c.messageId());
        assertThat(latest.hasMore()).isTrue();
        assertThat(latest.nextCursor()).isEqualTo(b.messageId());
        var older = service.history(id, Long.valueOf(latest.nextCursor()), null, 2);
        assertThat(older.items()).extracting("messageId").containsExactly(a.messageId());
        assertThat(older.hasMore()).isFalse();
        var newer = service.history(id, null, Long.valueOf(a.messageId()), 1);
        assertThat(newer.items()).extracting("messageId").containsExactly(b.messageId());
        assertThat(newer.nextCursor()).isEqualTo(b.messageId());
        assertThat(newer.hasMore()).isTrue();
    }

    @Test
    void rejectsMixedCursorsAndOversizedLimits() {
        Long id = createConversation();
        assertThatThrownBy(() -> service.history(id, 1L, 2L, 50)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.history(id, null, null, 101)).isInstanceOf(ApiException.class);
    }

    @Test
    void rollbackDoesNotPublishMessagesAndCommittedMessagesPublishAfterCommit() {
        Long id = createConversation();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.send(id, 101L, message("rollback", "Will not be saved"));
            assertThat(broadcast.delivered).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(messages.count()).isZero();
        assertThat(broadcast.delivered).isEmpty();
        var saved = service.send(id, 101L, message("commit", "Saved"));
        assertThat(broadcast.delivered).containsExactly(saved);
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesCreateOnlyOnePersistentMessage() throws Exception {
        Long id = createConversation();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<MessageResponse>) () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return service.send(id, 101L, message("concurrent", "Once"));
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).messageId()).isEqualTo(second.get(10, TimeUnit.SECONDS).messageId());
        }
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    void unreadCountsOnlyTheOtherPartysMessagesAfterLastRead() {
        Long id = createConversation();
        service.send(id, 101L, message("b1", "Hi"));
        service.send(id, 202L, message("s1", "Reply 1"));
        service.send(id, 202L, message("s2", "Reply 2"));

        assertThat(service.unreadCount().unreadCount()).isEqualTo(2);
        login(202L);
        assertThat(service.unreadCount().unreadCount()).isEqualTo(1);
        login(303L);
        assertThat(service.unreadCount().unreadCount()).isZero();

        login(101L);
        service.markRead(id);
        assertThat(service.unreadCount().unreadCount()).isZero();
        service.send(id, 202L, message("s3", "Reply 3"));
        assertThat(service.unreadCount().unreadCount()).isEqualTo(1);
        login(202L);
        assertThat(service.unreadCount().unreadCount()).isEqualTo(1);
    }

    @Test
    void markReadKeepsOneRowAtTheLatestMessageAndRejectsOutsiders() {
        Long id = createConversation();
        service.markRead(id);
        assertThat(reads.count()).isZero();

        service.send(id, 202L, message("s1", "One"));
        var latest = service.send(id, 202L, message("s2", "Two"));
        service.markRead(id);
        service.markRead(id);
        assertThat(reads.findAll()).singleElement()
                .satisfies(read -> assertThat(read.getLastReadMessageId()).isEqualTo(Long.valueOf(latest.messageId())));

        login(303L);
        assertThatThrownBy(() -> service.markRead(id))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_CONVERSATION_PARTICIPANT");
        assertThat(reads.count()).isEqualTo(1);
    }

    @Test
    void supplierSeesTheBuyerNameKeptOnTheRfq() {
        RfqEntity rfq = rfqs.findById(rfqId).orElseThrow();
        rfq.setCustomerNameSnapshot("Công ty Demo B2B");
        rfqs.save(rfq);
        Long id = createConversation();

        login(202L);
        assertThat(service.get(id).customerName()).isEqualTo("Công ty Demo B2B");
        assertThat(service.list(null, 0, 20, null).items()).extracting(ConversationResponse::customerName)
                .containsExactly("Công ty Demo B2B");
    }

    @Test
    void buyerNameIsAbsentForRfqsCreatedBeforeNamesWereKept() {
        Long id = createConversation();
        assertThat(service.get(id).customerName()).isNull();
    }

    private Long createConversation() {
        return Long.valueOf(service.create(rfqId, new CreateConversationRequest(77L)).conversation().conversationId());
    }

    private static FeignException.NotFound notFound() {
        var request = Request.create(Request.HttpMethod.GET, "/internal/v1/suppliers/by-user", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return new FeignException.NotFound("not found", request, null, Map.of());
    }

    private SendMessageRequest message(String clientId, String content) {
        return new SendMessageRequest(clientId, "TEXT", content);
    }

    private void login(Long userId) {
        var principal = new UserPrincipal(userId, "user@test", List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
