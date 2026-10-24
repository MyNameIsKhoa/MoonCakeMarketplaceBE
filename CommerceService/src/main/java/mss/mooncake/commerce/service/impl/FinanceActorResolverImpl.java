package mss.mooncake.commerce.service.impl;

import feign.FeignException;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.FinanceActorResolver;
import mss.mooncake.commerce.service.FinanceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** Resolves Identity-owned profile IDs before entering finance database transactions. */
@Service
public class FinanceActorResolverImpl implements FinanceActorResolver {
    private final IdentityServiceClient identity;

    public FinanceActorResolverImpl(IdentityServiceClient identity) {
        this.identity = identity;
    }

    public FinanceService.Actor resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CommercePrincipal principal)
                || principal.userId() == null || principal.userId() <= 0) {
            throw new FinanceException(HttpStatus.UNAUTHORIZED, "AUTHENTICATED_USER_ID_REQUIRED");
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        boolean supplier = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPPLIER".equals(a.getAuthority()));
        Long supplierId = null;
        if (supplier && !admin) {
            try {
                var summary = identity.getSupplierSummaryByUser(principal.userId());
                if (summary == null || !principal.userId().equals(summary.userId())
                        || summary.supplierId() == null || summary.supplierId() <= 0) {
                    throw new FinanceException(HttpStatus.FORBIDDEN, "SUPPLIER_CONTEXT_INVALID");
                }
                supplierId = summary.supplierId();
            } catch (FeignException.NotFound missing) {
                // No supplier profile grants no supplier access; customer access can still apply.
            } catch (FeignException unavailable) {
                throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_CONTEXT_UNAVAILABLE");
            }
        }
        return new FinanceService.Actor(principal.userId(), principal.customerId(), supplierId, admin);
    }
}
