package mss.mooncake.rfqnegotiation.client.dto.identity;

import java.util.Map;

/** POST /internal/v1/notifications on Identity: exactly one of userId, customerId, supplierId. */
public record CreateNotificationRequest(Long userId, Long customerId, Long supplierId, String type, String title,
                                        String body, Map<String, Object> target, String dedupKey) { }
