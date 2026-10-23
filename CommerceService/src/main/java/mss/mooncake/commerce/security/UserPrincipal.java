package mss.mooncake.commerce.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final Long customerId;
    private final String email;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(Long userId, Long customerId, String email, Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.customerId = customerId;
        this.email = email;
        this.authorities = authorities;
    }

    public static UserPrincipal create(Long userId, Long customerId, String email, List<String> roles) {
        List<SimpleGrantedAuthority> auths = (roles == null || roles.isEmpty())
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"))
                : roles.stream().map(SimpleGrantedAuthority::new).toList();
        return new UserPrincipal(userId, customerId, email, auths);
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getEmail() {
        return email;
    }

    public boolean hasRole(String role) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(role));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
