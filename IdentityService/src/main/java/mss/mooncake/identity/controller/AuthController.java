package mss.mooncake.identity.controller;

import jakarta.validation.Valid;
import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.response.UserSummaryResponse;
import mss.mooncake.identity.service.AccountApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountApplicationService accountApplicationService;

    public AuthController(AccountApplicationService accountApplicationService) {
        this.accountApplicationService = accountApplicationService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserSummaryResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(accountApplicationService.register(request));
    }
}
