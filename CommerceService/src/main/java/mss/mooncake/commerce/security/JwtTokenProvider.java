package mss.mooncake.commerce.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {
    private final SecretKey signingKey;
    private final String issuer;
    private final String audience;

    public JwtTokenProvider(@Value("${security.jwt.secret}") String secret,
                            @Value("${security.jwt.issuer}") String issuer,
                            @Value("${security.jwt.audience}") String audience) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.audience = audience;
    }

    public Claims parseClaims(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(signingKey).requireIssuer(issuer)
                    .requireAudience(audience).build().parseClaimsJws(token).getBody();
            Date expiration = claims.getExpiration();
            List<?> roles = claims.get("roles", List.class);
            return expiration != null && expiration.after(new Date())
                    && claims.getSubject() != null && claims.get("userId") != null
                    && roles != null && !roles.isEmpty() ? claims : null;
        } catch (RuntimeException invalidToken) {
            return null;
        }
    }
}
