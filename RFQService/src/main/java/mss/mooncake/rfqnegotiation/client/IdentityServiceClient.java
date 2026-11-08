package mss.mooncake.rfqnegotiation.client;
import mss.mooncake.rfqnegotiation.client.dto.identity.CreateNotificationRequest;
import mss.mooncake.rfqnegotiation.client.dto.identity.UserSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.AddressSummaryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
@FeignClient(name = "identity-service", url = "${services.identity.base-url:http://localhost:8081}", configuration = IdentityFeignConfig.class)
public interface IdentityServiceClient {
    @GetMapping("/internal/v1/users/{userId}/summary") UserSummaryResponse getUserSummary(@PathVariable Long userId);
    @GetMapping("/internal/v1/suppliers/by-user/{userId}/summary") SupplierSummaryResponse getSupplierByUser(@PathVariable Long userId);
    @GetMapping("/internal/v1/suppliers/{supplierId}/summary") SupplierSummaryResponse getSupplierSummary(@PathVariable Long supplierId);
    @GetMapping("/internal/v1/addresses/{addressId}/summary") AddressSummaryResponse getAddressSummary(@PathVariable Long addressId);
    @PostMapping("/internal/v1/notifications") void createNotification(@RequestBody CreateNotificationRequest request);
}
