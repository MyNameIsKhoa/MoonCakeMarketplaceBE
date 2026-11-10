package mss.mooncake.identity.controller;

import jakarta.validation.Valid;
import mss.mooncake.identity.dto.request.LoginRequest;
import mss.mooncake.identity.dto.request.RegisterRequest;
import mss.mooncake.identity.dto.request.GoogleLoginRequest;
import mss.mooncake.identity.dto.request.EmailAddressRequest;
import mss.mooncake.identity.dto.request.TokenRequest;
import mss.mooncake.identity.dto.request.PasswordResetRequest;
import mss.mooncake.identity.dto.response.AuthResponse;
import mss.mooncake.identity.dto.response.UserSummaryResponse;
import mss.mooncake.identity.service.AccountApplicationService;
import mss.mooncake.identity.service.GoogleIdentityTokenService;
import mss.mooncake.identity.service.AuthEmailFlowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountApplicationService accountApplicationService;
    private final GoogleIdentityTokenService googleIdentityTokenService;
    private final AuthEmailFlowService authEmailFlowService;

    public AuthController(AccountApplicationService accountApplicationService,
                          GoogleIdentityTokenService googleIdentityTokenService,
                          AuthEmailFlowService authEmailFlowService) {
        this.accountApplicationService = accountApplicationService;
        this.googleIdentityTokenService = googleIdentityTokenService;
        this.authEmailFlowService = authEmailFlowService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserSummaryResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountApplicationService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(accountApplicationService.login(request));
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        var identity = googleIdentityTokenService.verify(request.idToken());
        return ResponseEntity.ok(accountApplicationService.loginWithGoogle(identity.email(), identity.name()));
    }

    @PostMapping("/email/verification-requests")
    public ResponseEntity<Void> requestEmailVerification(@Valid @RequestBody EmailAddressRequest request) {
        authEmailFlowService.requestVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/email/verify")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
        authEmailFlowService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset-requests")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody EmailAddressRequest request) {
        authEmailFlowService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-resets")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authEmailFlowService.resetPassword(request.code(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

}
