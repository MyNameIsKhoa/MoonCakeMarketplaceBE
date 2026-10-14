package mss.mooncake.commerce.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) { this.tokenProvider = tokenProvider; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            Claims claims = tokenProvider.parseClaims(authorization.substring(7));
            if (claims != null && claims.get("userId") != null) {
                Object roleClaim = claims.get("roles");
                @SuppressWarnings("unchecked")
                List<String> roleList = roleClaim instanceof List<?> roles
                        ? roles.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                        : List.of();
                var authorities = roleList.stream().map(SimpleGrantedAuthority::new).toList();
                Number userIdNum = claims.get("userId", Number.class);
                Number custIdNum = claims.get("customerId", Number.class);
                Long userId = userIdNum != null ? userIdNum.longValue() : null;
                Long customerId = custIdNum != null ? custIdNum.longValue() : null;
                UserPrincipal principal = UserPrincipal.create(userId, customerId, claims.getSubject(), roleList);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null, authorities));
            }
        }
        chain.doFilter(request, response);
    }
}
