package mss.mooncake.identity.controller;

import jakarta.validation.Valid;
import mss.mooncake.identity.dto.request.SavePayoutAccountRequest;
import mss.mooncake.identity.dto.response.PayoutAccountResponse;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.service.PayoutAccountService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/payout-accounts")
public class PayoutAccountController {
    private final PayoutAccountService service;
    public PayoutAccountController(PayoutAccountService service) { this.service = service; }

    @GetMapping("/banks")
    public java.util.List<PayoutAccountService.Bank> banks() { return service.banks(); }

    @GetMapping("/{ownerKind}")
    public PayoutAccountResponse mine(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String ownerKind) {
        if (principal == null) throw new UnauthorizedException("User is not authenticated");
        return service.mine(principal.getUserId(), ownerKind);
    }

    @PutMapping("/{ownerKind}")
    public PayoutAccountResponse save(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String ownerKind,
            @Valid @RequestBody SavePayoutAccountRequest request) {
        if (principal == null) throw new UnauthorizedException("User is not authenticated");
        return service.save(principal.getUserId(), ownerKind, request);
    }
}
