package mss.mooncake.commerce.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;

public record CommercePrincipal(
        Long userId,
        Long customerId,
        String email,
        Collection<? extends GrantedAuthority> authorities
) {
    public CommercePrincipal(Long userId, Long customerId, String email) {
        this(userId, customerId, email, List.of());
    }

    public boolean hasRole(String role) {
        return authorities != null && authorities.stream().anyMatch(a -> a.getAuthority().equals(role));
    }
}
