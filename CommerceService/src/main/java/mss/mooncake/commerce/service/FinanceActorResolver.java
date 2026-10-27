package mss.mooncake.commerce.service;

import org.springframework.security.core.Authentication;

public interface FinanceActorResolver {
    FinanceService.Actor resolve(Authentication authentication);
}
