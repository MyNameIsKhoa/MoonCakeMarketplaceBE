package mss.mooncake.identity.service;

import mss.mooncake.identity.dto.request.CreateNotificationRequest;
import mss.mooncake.identity.dto.response.InternalNotificationResponse;
import mss.mooncake.identity.dto.response.NotificationPageResponse;
import mss.mooncake.identity.dto.response.NotificationResponse;

/** In-app notifications. Never sends email: other services only write rows here, users read their own. */
public interface NotificationService {
    NotificationPageResponse list(Long userId, int page, int size);
    long unreadCount(Long userId);
    /** Another user's notification answers 404, the same as a missing one. Marking twice keeps the first readAt. */
    NotificationResponse markRead(Long userId, Long notificationId);
    int markAllRead(Long userId);
    InternalNotificationResponse create(CreateNotificationRequest request);
}
