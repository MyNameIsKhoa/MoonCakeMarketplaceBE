package mss.mooncake.rfqnegotiation.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.rfqnegotiation.entity.ConversationEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationConversionEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RfqNotifierTest {
    @Mock IdentityServiceClient identity;
    RfqNotifier notifier;
    RfqEntity rfq;
    QuotationEntity quotation;

    @BeforeEach
    void setUp() {
        notifier = new RfqNotifier(identity);
        rfq = new RfqEntity();
        ReflectionTestUtils.setField(rfq, "id", 5L);
        rfq.setTitle("Bánh trung thu cho công ty");
        rfq.setCustomerIdRef(101L);
        rfq.setQuantity(100);
        quotation = new QuotationEntity();
        ReflectionTestUtils.setField(quotation, "id", 9L);
        quotation.setRfqId(5L);
        quotation.setSupplierIdRef(202L);
        quotation.setVersionNo(1);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CreateNotificationRequest sent() {
        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity).createNotification(captor.capture());
        return captor.getValue();
    }

    @Test
    void newQuotationGoesToTheBuyerWithAQuotationTarget() {
        notifier.quotationSubmitted(rfq, quotation, "Tiệm Bánh Như Lan");

        var request = sent();
        assertThat(request.customerId()).isEqualTo(101L);
        assertThat(request.userId()).isNull();
        assertThat(request.supplierId()).isNull();
        assertThat(request.type()).isEqualTo("QUOTATION_RECEIVED");
        assertThat(request.body()).contains("Tiệm Bánh Như Lan").contains("Bánh trung thu cho công ty");
        assertThat(request.target()).isEqualTo(Map.of("kind", "QUOTATION", "rfqId", "5", "quotationId", "9"));
        assertThat(request.dedupKey()).isEqualTo("QUOTATION_RECEIVED:9");
    }

    @Test
    void revisionIsReportedAsARevisedQuotation() {
        quotation.setPreviousQuotationId(8L);
        quotation.setVersionNo(2);

        notifier.quotationSubmitted(rfq, quotation, "Tiệm Bánh Như Lan");

        assertThat(sent().type()).isEqualTo("QUOTATION_REVISED");
    }

    @Test
    void supplierSideNotificationsAreAddressedBySupplierAndMarkedForTheSellerCenter() {
        quotation.setDecisionReason("Giá cao hơn ngân sách");
        notifier.quotationRejected(rfq, quotation);

        var request = sent();
        assertThat(request.supplierId()).isEqualTo(202L);
        assertThat(request.type()).isEqualTo("QUOTATION_REJECTED");
        assertThat(request.body()).contains("Giá cao hơn ngân sách");
        assertThat(request.target()).containsEntry("role", "SELLER").containsEntry("kind", "QUOTATION");
    }

    @Test
    void privateRfqNotifiesEachInvitedSupplier() {
        notifier.rfqInvited(rfq, List.of(202L, 303L));

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity, times(2)).createNotification(captor.capture());
        assertThat(captor.getAllValues()).extracting(CreateNotificationRequest::supplierId).containsExactly(202L, 303L);
        assertThat(captor.getAllValues()).extracting(CreateNotificationRequest::type).containsOnly("RFQ_INVITED");
        assertThat(captor.getAllValues().getFirst().target()).isEqualTo(Map.of("kind", "RFQ", "id", "5", "role", "SELLER"));
    }

    @Test
    void cancelledOrderNotifiesBothSidesWithTheirOwnOrder() {
        var conversion = new QuotationConversionEntity();
        conversion.setOrderIdRef(501L);
        conversion.setSellerOrderIdRef(601L);

        notifier.orderCancelled(rfq, quotation, conversion);

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity, times(2)).createNotification(captor.capture());
        var buyer = captor.getAllValues().stream().filter(r -> r.customerId() != null).findFirst().orElseThrow();
        var seller = captor.getAllValues().stream().filter(r -> r.supplierId() != null).findFirst().orElseThrow();
        assertThat(buyer.target()).isEqualTo(Map.of("kind", "ORDER", "id", "501"));
        assertThat(buyer.dedupKey()).isEqualTo("ORDER_CANCELLED:501");
        assertThat(seller.supplierId()).isEqualTo(202L);
        assertThat(seller.target()).isEqualTo(Map.of("kind", "ORDER", "id", "601", "role", "SELLER"));
        assertThat(seller.dedupKey()).isEqualTo("SELLER_ORDER_CANCELLED:601");
    }

    @Test
    void chatMessageGoesToTheOtherSideAndCollapsesUntilTheRecipientReads() {
        var conversation = new ConversationEntity();
        ReflectionTestUtils.setField(conversation, "id", 77L);
        conversation.setRfqId(5L);
        conversation.setCustomerIdRef(101L);
        conversation.setSupplierIdRef(202L);

        notifier.chatMessage(conversation, true, "Công ty Demo", "Cho mình hỏi giá hộp 4 bánh", null);
        notifier.chatMessage(conversation, false, "Xưởng bánh", "x".repeat(500), 40L);

        ArgumentCaptor<CreateNotificationRequest> captor = ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(identity, times(2)).createNotification(captor.capture());
        var toSupplier = captor.getAllValues().get(0);
        var toBuyer = captor.getAllValues().get(1);
        assertThat(toSupplier.supplierId()).isEqualTo(202L);
        assertThat(toSupplier.type()).isEqualTo("CHAT_MESSAGE");
        assertThat(toSupplier.title()).contains("Công ty Demo");
        assertThat(toSupplier.target()).isEqualTo(Map.of("kind", "CONVERSATION", "id", "77", "rfqId", "5"));
        // One key per unread streak: it only changes after the recipient reads the conversation.
        assertThat(toSupplier.dedupKey()).isEqualTo("CHAT_MESSAGE:77:0");
        assertThat(toBuyer.customerId()).isEqualTo(101L);
        assertThat(toBuyer.dedupKey()).isEqualTo("CHAT_MESSAGE:77:40");
        assertThat(toBuyer.body().length()).isLessThanOrEqualTo(200);
    }

    @Test
    void identityFailuresAreSwallowed() {
        var request = Request.create(Request.HttpMethod.POST, "http://identity/internal", Map.of(), null, StandardCharsets.UTF_8, null);
        doThrow(new FeignException.ServiceUnavailable("down", request, null, null))
                .when(identity).createNotification(any());

        assertThatCode(() -> notifier.quotationSubmitted(rfq, quotation, "Shop")).doesNotThrowAnyException();
        doThrow(new IllegalStateException("boom")).when(identity).createNotification(any());
        assertThatCode(() -> notifier.quotationRejected(rfq, quotation)).doesNotThrowAnyException();
    }

    @Test
    void insideATransactionNothingIsSentBeforeCommitAndNothingAfterRollback() {
        TransactionSynchronizationManager.initSynchronization();
        notifier.quotationSubmitted(rfq, quotation, "Shop");
        verifyNoInteractions(identity);

        List<TransactionSynchronization> pending = TransactionSynchronizationManager.getSynchronizations();
        pending.forEach(TransactionSynchronization::afterCommit);
        verify(identity).createNotification(any());

        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        notifier.quotationRejected(rfq, quotation);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(identity, times(1)).createNotification(any());
    }
}
