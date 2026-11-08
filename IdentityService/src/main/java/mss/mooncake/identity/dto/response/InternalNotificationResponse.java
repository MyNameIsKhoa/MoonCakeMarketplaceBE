package mss.mooncake.identity.dto.response;

/** created=false when the dedupKey already existed for this user (the existing row is returned). */
public record InternalNotificationResponse(Long notificationId, Long userId, boolean created) { }
