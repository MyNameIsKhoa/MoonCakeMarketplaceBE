package mss.mooncake.rfqnegotiation.dto.response;

import java.time.Instant;
import mss.mooncake.rfqnegotiation.enums.ConversationStatus;

/** customerName: the buyer's name kept on the RFQ; null for RFQs created before names were kept. */
public record ConversationResponse(String conversationId, String rfqId, String customerId,
        String supplierId, ConversationStatus status, Instant createdAt, String customerName) { }
