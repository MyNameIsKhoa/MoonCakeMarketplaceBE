package mss.mooncake.rfqnegotiation.client;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
public class CatalogFeignConfig {
    @Bean RequestInterceptor catalogServiceAuthentication(@Value("${security.outgoing-catalog-token}") String token) {
        return template -> { template.header("X-Service-Name", "rfq"); template.header("X-Service-Token", token); };
    }
}
