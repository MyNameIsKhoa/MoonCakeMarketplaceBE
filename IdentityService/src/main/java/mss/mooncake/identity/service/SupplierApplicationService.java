package mss.mooncake.identity.service;

import java.util.List;
import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.dto.response.SupplierSummaryResponse;
import mss.mooncake.identity.dto.response.SupplierVerificationAuditResponse;
import mss.mooncake.identity.enums.SupplierVerificationStatus;

public interface SupplierApplicationService {
    SupplierProfileResponse registerSupplier(Long userId, SupplierProfileRequest request);
    SupplierProfileResponse getSupplierProfile(Long userId);
    SupplierProfileResponse updateSupplierProfile(Long userId, UpdateSupplierProfileRequest request);
    SupplierSummaryResponse getSupplierSummary(Long supplierId);
    SupplierSummaryResponse getSupplierSummaryByUserId(Long userId);
    List<SupplierProfileResponse> getSupplierProfilesByStatus(SupplierVerificationStatus status);
    SupplierProfileResponse reviewSupplier(Long supplierId, SupplierVerificationStatus newStatus, Long actorUserId, String reason);
    List<SupplierVerificationAuditResponse> getVerificationHistory(Long supplierId);
}
