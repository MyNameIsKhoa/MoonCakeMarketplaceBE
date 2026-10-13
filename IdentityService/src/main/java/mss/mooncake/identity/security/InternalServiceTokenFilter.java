package mss.mooncake.identity.security;

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
    private final Map<String, byte[]> expectedTokens;

    public InternalServiceTokenFilter(@Value("${security.internal-service-tokens.catalog}") String catalog,
                                      @Value("${security.internal-service-tokens.commerce}") String commerce,
                                      @Value("${security.internal-service-tokens.rfq}") String rfq) {
        this.expectedTokens = Map.of("catalog", token(catalog), "commerce", token(commerce), "rfq", token(rfq));
    }

    private byte[] token(String value) {
        if (value == null || value.length() < 32) throw new IllegalArgumentException("Each internal service token must contain at least 32 characters");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caller = request.getHeader("X-Service-Name");
        String suppliedToken = request.getHeader("X-Service-Token");
        byte[] expected = caller == null ? null : expectedTokens.get(caller);
        if (expected != null && suppliedToken != null && MessageDigest.isEqual(expected, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    caller, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL_" + caller.toUpperCase())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }
}
