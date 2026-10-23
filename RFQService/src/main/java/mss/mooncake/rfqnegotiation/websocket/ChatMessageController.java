package mss.mooncake.rfqnegotiation.websocket;

import jakarta.validation.Valid;
import java.security.Principal;
import mss.mooncake.rfqnegotiation.dto.request.SendMessageRequest;
import mss.mooncake.rfqnegotiation.service.ConversationApplicationService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
public class ChatMessageController {
    private final ConversationApplicationService conversations;
    public ChatMessageController(ConversationApplicationService conversations) { this.conversations = conversations; }

    @MessageMapping("/conversations/{conversationId}/messages")
    public void send(@DestinationVariable Long conversationId, @Valid @Payload SendMessageRequest request,
            Principal principal) {
        conversations.send(conversationId, ChatStompInterceptor.userId(principal), request);
    }
}
