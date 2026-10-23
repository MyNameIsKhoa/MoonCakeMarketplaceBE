package mss.mooncake.rfqnegotiation.dto.response;

import java.time.Instant;

public record MessageResponse(String messageId, String conversationId, String senderUserId,
        String messageType, String content, Instant sentAt, String clientMessageId) { }
