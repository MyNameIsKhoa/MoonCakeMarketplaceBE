package mss.mooncake.identity.controller;

import jakarta.validation.Valid;
import mss.mooncake.identity.dto.request.UpdateProfileRequest;
import mss.mooncake.identity.dto.response.UserProfileResponse;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.service.AccountApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AccountApplicationService accountApplicationService;

    public UserController(AccountApplicationService accountApplicationService) {
        this.accountApplicationService = accountApplicationService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return ResponseEntity.ok(accountApplicationService.getProfile(principal.getUserId()));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserProfileResponse> updateCurrentUser(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @Valid @RequestBody UpdateProfileRequest request) {
        if (principal == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return ResponseEntity.ok(accountApplicationService.updateProfile(principal.getUserId(), request));
    }
}
