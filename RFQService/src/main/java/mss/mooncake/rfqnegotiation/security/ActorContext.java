package mss.mooncake.rfqnegotiation.security;

import feign.FeignException;
import java.util.Objects;
import mss.mooncake.rfqnegotiation.client.IdentityServiceClient;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Maps an authenticated user to domain IDs; userId, customerId and supplierId are distinct. */
@Component
public class ActorContext {
    private final IdentityServiceClient identity;
    public ActorContext(IdentityServiceClient identity) { this.identity = identity; }

    public Long userId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Vui lòng đăng nhập");
        }
        return principal.userId();
    }

    public Long optionalCustomerId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) return null;
        try {
            var summary = identity.getUserSummary(userId());
            if (summary == null || !Objects.equals(summary.userId(), userId())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Không xác minh được tài khoản");
            }
            return summary.customerId();
        } catch (FeignException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Không xác minh được tài khoản");
        }
    }

    /** Display name of the signed-in user; null when Identity cannot tell, since the name is display-only. */
    public String fullName() {
        try {
            var summary = identity.getUserSummary(userId());
            return summary == null ? null : summary.fullName();
        } catch (FeignException ex) {
            return null;
        }
    }

    public Long customerId() {
        userId();
        Long id = optionalCustomerId();
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "CUSTOMER_REQUIRED", "Tài khoản chưa có hồ sơ khách hàng");
        return id;
    }

    public Long supplierId() {
        Long id = optionalSupplierId();
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "SUPPLIER_REQUIRED", "Tài khoản chưa có hồ sơ nhà cung cấp");
        return id;
    }

    /** Supplier allowed to trade (quote, revise): VERIFIED in Identity. PENDING/REJECTED/SUSPENDED may only read. */
    public Long verifiedSupplierId() {
        try {
            var summary = identity.getSupplierByUser(userId());
            if (summary == null || !Objects.equals(summary.userId(), userId()) || summary.supplierId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SUPPLIER_REQUIRED", "Tài khoản chưa có hồ sơ nhà cung cấp");
            }
            if (!"VERIFIED".equals(summary.verificationStatus())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SUPPLIER_NOT_VERIFIED", "Hồ sơ nhà cung cấp chưa được xác minh hoặc đang bị khóa");
            }
            return summary.supplierId();
        } catch (FeignException.NotFound ex) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SUPPLIER_REQUIRED", "Tài khoản chưa có hồ sơ nhà cung cấp");
        } catch (FeignException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Không xác minh được nhà cung cấp");
        }
    }

    public void assertVerifiedSupplierOwner(Long owner) {
        if (!Objects.equals(verifiedSupplierId(), owner)) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_RESOURCE_OWNER", "Bạn không sở hữu tài nguyên này");
    }

    public Long optionalSupplierId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) return null;
        try {
            var summary = identity.getSupplierByUser(userId());
            if (summary == null || !Objects.equals(summary.userId(), userId()) || summary.supplierId() == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "SUPPLIER_REQUIRED", "Tài khoản chưa có hồ sơ nhà cung cấp");
            }
            return summary.supplierId();
        } catch (FeignException.NotFound ex) {
            return null;
        } catch (FeignException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Không xác minh được nhà cung cấp");
        }
    }

    public void assertCustomerOwner(Long owner) {
        if (!Objects.equals(customerId(), owner)) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_RESOURCE_OWNER", "Bạn không sở hữu tài nguyên này");
    }

    public void assertSupplierOwner(Long owner) {
        if (!Objects.equals(supplierId(), owner)) throw new ApiException(HttpStatus.FORBIDDEN, "NOT_RESOURCE_OWNER", "Bạn không sở hữu tài nguyên này");
    }
}
