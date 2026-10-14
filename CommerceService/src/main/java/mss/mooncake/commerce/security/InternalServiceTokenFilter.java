package mss.mooncake.commerce.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalServiceTokenFilter extends OncePerRequestFilter {
    private final byte[] expectedToken;

    public InternalServiceTokenFilter(@Value("${security.internal-service-tokens.rfq}") String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("RFQ_TO_COMMERCE_TOKEN must contain at least 32 characters");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String suppliedToken = request.getHeader("X-Service-Token");
        if ("rfq".equals(request.getHeader("X-Service-Name")) && suppliedToken != null && MessageDigest.isEqual(
                expectedToken, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "rfq", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_RFQ")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
