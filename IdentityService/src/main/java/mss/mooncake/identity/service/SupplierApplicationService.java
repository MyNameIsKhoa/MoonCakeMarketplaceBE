package mss.mooncake.identity.service;

import java.util.List;
import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.dto.response.SupplierSummaryResponse;
import mss.mooncake.identity.dto.response.SupplierVerificationAuditResponse;
import mss.mooncake.identity.enums.SupplierVerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupplierApplicationService {
    SupplierProfileResponse registerSupplier(Long userId, SupplierProfileRequest request);
    SupplierProfileResponse getSupplierProfile(Long userId);
    SupplierProfileResponse updateSupplierProfile(Long userId, UpdateSupplierProfileRequest request);
    SupplierSummaryResponse getSupplierSummary(Long supplierId);
    SupplierSummaryResponse getSupplierSummaryByUserId(Long userId);
    Page<SupplierProfileResponse> getSupplierProfilesByStatus(SupplierVerificationStatus status, Pageable pageable);
    SupplierProfileResponse reviewSupplier(Long supplierId, SupplierVerificationStatus newStatus, Long actorUserId, String reason);
    List<SupplierVerificationAuditResponse> getVerificationHistory(Long supplierId);
}
