package mss.mooncake.rfqnegotiation.client;
import mss.mooncake.rfqnegotiation.client.dto.identity.UserSummaryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
@FeignClient(name = "identity-service", url = "${services.identity.base-url:http://localhost:8081}", configuration = IdentityFeignConfig.class)
public interface IdentityServiceClient {
    @GetMapping("/api/user/{userId}/summary") UserSummaryResponse getUserSummary(@PathVariable Long userId);
}
