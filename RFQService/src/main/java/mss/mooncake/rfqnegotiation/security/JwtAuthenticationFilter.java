package mss.mooncake.rfqnegotiation.security;

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
                List<SimpleGrantedAuthority> authorities = roleClaim instanceof List<?> roles
                        ? roles.stream().filter(String.class::isInstance).map(String.class::cast)
                                .map(SimpleGrantedAuthority::new).toList()
                        : List.<SimpleGrantedAuthority>of();
                try {
                    Long userId = Long.valueOf(claims.get("userId").toString());
                    if (userId > 0) SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            new UserPrincipal(userId, claims.getSubject(), authorities), null, authorities));
                } catch (NumberFormatException invalidUserId) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
