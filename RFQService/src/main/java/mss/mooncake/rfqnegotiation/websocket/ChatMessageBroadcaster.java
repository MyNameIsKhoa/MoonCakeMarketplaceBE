package mss.mooncake.rfqnegotiation.websocket;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatMessageBroadcaster {
    private final SimpMessagingTemplate broker;
    public ChatMessageBroadcaster(SimpMessagingTemplate broker) { this.broker = broker; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcast(MessagePersistedEvent event) {
        broker.convertAndSend("/topic/conversations/" + event.message().conversationId(), event.message());
    }
}
