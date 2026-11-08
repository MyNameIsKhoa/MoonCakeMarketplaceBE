package mss.mooncake.identity.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import mss.mooncake.identity.dto.request.CreateNotificationRequest;
import mss.mooncake.identity.dto.response.InternalNotificationResponse;
import mss.mooncake.identity.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service: Commerce and RFQ create in-app notifications (X-Service-Name/X-Service-Token). */
@RestController
@RequestMapping("/internal/v1/notifications")
@Hidden
public class InternalNotificationController {
    private final NotificationService notifications;

    public InternalNotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** 201 when created; 200 with the existing row when dedupKey was already used for this user. */
    @PostMapping
    public ResponseEntity<InternalNotificationResponse> create(@Valid @RequestBody CreateNotificationRequest request) {
        InternalNotificationResponse result = notifications.create(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }
}
