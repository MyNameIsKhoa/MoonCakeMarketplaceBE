package mss.mooncake.commerce.client;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
public class IdentityFeignConfig {
    @Bean RequestInterceptor identityServiceAuthentication(@Value("${security.outgoing-identity-token}") String token) {
        return template -> { template.header("X-Service-Name", "commerce"); template.header("X-Service-Token", token); };
    }
}
