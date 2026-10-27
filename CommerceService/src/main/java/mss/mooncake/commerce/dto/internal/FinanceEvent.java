package mss.mooncake.commerce.dto.internal;

public record FinanceEvent(String eventId, long sellerOrderId, long aggregateVersion,
        String eventType, long resourceId) { }
