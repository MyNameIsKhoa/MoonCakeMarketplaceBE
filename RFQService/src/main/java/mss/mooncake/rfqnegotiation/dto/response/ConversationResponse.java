package mss.mooncake.rfqnegotiation.dto.response;

import java.time.Instant;
import mss.mooncake.rfqnegotiation.enums.ConversationStatus;

public record ConversationResponse(String conversationId, String rfqId, String customerId,
        String supplierId, ConversationStatus status, Instant createdAt) { }
