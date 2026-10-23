package mss.mooncake.catalogstore.client;

import mss.mooncake.catalogstore.client.dto.SupplierSummaryDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign client gọi Identity Service để xác minh supplier.
 * Gọi endpoint /api/supplier/by-user/{userId}/summary.
 */
@FeignClient(name = "identity-service", url = "${clients.identity.url:http://localhost:8081}", configuration = mss.mooncake.catalogstore.config.FeignConfig.class)
public interface IdentityClient {

    /**
     * Lấy SupplierSummary theo supplierId (supplier_id trong Identity DB).
     */
    @GetMapping("/api/supplier/{id}/summary")
    SupplierSummaryDto getSupplierSummaryById(@PathVariable("id") Long supplierId);

    /**
     * Lấy SupplierSummary theo userId của người đang login.
     * Dùng khi POST /api/stores: supplier tạo store phải có hồ sơ supplier ứng với userId.
     */
    @GetMapping("/api/supplier/by-user/{userId}/summary")
    SupplierSummaryDto getSupplierSummaryByUserId(@PathVariable("userId") Long userId);
}
