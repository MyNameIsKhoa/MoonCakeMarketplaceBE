package mss.mooncake.identity.controller;

import io.swagger.v3.oas.annotations.Hidden;
import mss.mooncake.identity.dto.response.PayoutAccountResponse;
import mss.mooncake.identity.service.PayoutAccountService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/payout-accounts")
@Hidden
public class InternalPayoutAccountController {
    private final PayoutAccountService service;
    public InternalPayoutAccountController(PayoutAccountService service) { this.service = service; }

    @GetMapping("/customers/{customerId}")
    public PayoutAccountResponse customer(@PathVariable Long customerId) { return service.customer(customerId); }

    @GetMapping("/suppliers/{supplierId}")
    public PayoutAccountResponse supplier(@PathVariable Long supplierId) { return service.supplier(supplierId); }
}
