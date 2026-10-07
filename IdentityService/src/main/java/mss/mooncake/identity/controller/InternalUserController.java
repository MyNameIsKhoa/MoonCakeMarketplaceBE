package mss.mooncake.identity.controller;

import mss.mooncake.identity.dto.response.UserSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/users")
public class InternalUserController {
    @GetMapping("/{userId}/summary")
    public ResponseEntity<UserSummaryResponse> getSummary(@PathVariable Long userId) {
        // TODO: Query Identity-owned data and return a stable cross-service summary.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
