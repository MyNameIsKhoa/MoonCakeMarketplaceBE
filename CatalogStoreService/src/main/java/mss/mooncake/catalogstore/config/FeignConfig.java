package mss.mooncake.catalogstore.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {
    @Bean
    RequestInterceptor internalServiceAuthentication(@Value("${security.outgoing-identity-token}") String token) {
        return template -> {
            template.header("X-Service-Name", "catalog");
            template.header("X-Service-Token", token);
        };
    }
}
