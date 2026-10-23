package mss.mooncake.commerce.client;

import mss.mooncake.commerce.client.dto.identity.UserSummaryResponse;
import mss.mooncake.commerce.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.commerce.client.dto.identity.AddressSummaryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "identity-service", url = "${services.identity.base-url:http://localhost:8081}", configuration = IdentityFeignConfig.class)
public interface IdentityServiceClient {
    @GetMapping("/api/user/{userId}/summary")
    UserSummaryResponse getUserSummary(@PathVariable Long userId);
    @GetMapping("/api/supplier/by-user/{userId}/summary")
    SupplierSummaryResponse getSupplierSummaryByUser(@PathVariable Long userId);

    @GetMapping("/api/address/{addressId}/summary")
    AddressSummaryResponse getAddressSummary(@PathVariable Long addressId);
}
