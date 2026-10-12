package mss.mooncake.catalogstore.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class UserPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserPrincipal(Long userId, String email, Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.email = email;
        this.authorities = authorities;
    }

    public static UserPrincipal create(Long userId, String email, List<String> roles) {
        List<SimpleGrantedAuthority> auths = (roles == null || roles.isEmpty())
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"))
                : roles.stream().map(SimpleGrantedAuthority::new).toList();
        return new UserPrincipal(userId, email, auths);
    }

    public Long getUserId() { return userId; }

    public String getEmail() { return email; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return null; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
