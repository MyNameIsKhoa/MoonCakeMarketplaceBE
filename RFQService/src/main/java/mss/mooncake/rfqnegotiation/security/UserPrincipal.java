package mss.mooncake.rfqnegotiation.security;

import java.security.Principal;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;

public record UserPrincipal(Long userId, String email, List<? extends GrantedAuthority> authorities) implements Principal {
    @Override public String getName() { return userId.toString(); }
}
