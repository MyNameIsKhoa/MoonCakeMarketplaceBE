package mss.mooncake.identity.controller;

import mss.mooncake.identity.dto.response.UserSummaryResponse;
import mss.mooncake.identity.service.AccountApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/users")
public class InternalUserController {

    private final AccountApplicationService accountApplicationService;

    public InternalUserController(AccountApplicationService accountApplicationService) {
        this.accountApplicationService = accountApplicationService;
    }

    @GetMapping("/{userId}/summary")
    public ResponseEntity<UserSummaryResponse> getSummary(@PathVariable Long userId) {
        return ResponseEntity.ok(accountApplicationService.getUserSummary(userId));
    }
}
