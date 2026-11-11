package mss.mooncake.identity.controller;

import mss.mooncake.identity.dto.response.NotificationPageResponse;
import mss.mooncake.identity.dto.response.NotificationResponse;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own in-app notifications. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    public record UnreadCountResponse(long unreadCount) { }
    public record MarkAllReadResponse(int updated) { }

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** Newest first; size 1..50. */
    @GetMapping
    public NotificationPageResponse list(@AuthenticationPrincipal UserPrincipal principal,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return notifications.list(userId(principal), page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return new UnreadCountResponse(notifications.unreadCount(userId(principal)));
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal UserPrincipal principal, @PathVariable("id") Long id) {
        return notifications.markRead(userId(principal), id);
    }

    @PostMapping("/read-all")
    public MarkAllReadResponse markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        return new MarkAllReadResponse(notifications.markAllRead(userId(principal)));
    }

    private static Long userId(UserPrincipal principal) {
        if (principal == null) throw new UnauthorizedException("Vui lòng đăng nhập");
        return principal.getUserId();
    }
}
