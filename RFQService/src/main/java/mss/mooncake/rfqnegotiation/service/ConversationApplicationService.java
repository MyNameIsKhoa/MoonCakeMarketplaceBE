package mss.mooncake.rfqnegotiation.service;

import mss.mooncake.rfqnegotiation.dto.request.CreateConversationRequest;
import mss.mooncake.rfqnegotiation.dto.request.SendMessageRequest;
import mss.mooncake.rfqnegotiation.dto.response.ConversationCreatedResponse;
import mss.mooncake.rfqnegotiation.dto.response.ConversationResponse;
import mss.mooncake.rfqnegotiation.dto.response.MessagePageResponse;
import mss.mooncake.rfqnegotiation.dto.response.MessageResponse;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.UnreadCountResponse;

public interface ConversationApplicationService {
    ConversationCreatedResponse create(Long rfqId, CreateConversationRequest request);
    PageResponse<ConversationResponse> list(Long rfqId, int page, int size, String sort);
    ConversationResponse get(Long conversationId);
    MessagePageResponse history(Long conversationId, Long beforeMessageId, Long afterMessageId, int limit);
    void assertParticipant(Long conversationId, Long userId);
    MessageResponse send(Long conversationId, Long userId, SendMessageRequest request);
    UnreadCountResponse unreadCount();
    /** Marks every message of the conversation up to the newest one as read by the caller. */
    void markRead(Long conversationId);
}
