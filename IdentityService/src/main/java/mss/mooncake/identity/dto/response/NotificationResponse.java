package mss.mooncake.identity.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

/** createdAt/readAt are UTC without a zone suffix, like other Identity timestamps. */
public record NotificationResponse(Long notificationId, String type, String title, String body,
                                   Map<String, Object> target, LocalDateTime createdAt, LocalDateTime readAt,
                                   boolean read) { }
