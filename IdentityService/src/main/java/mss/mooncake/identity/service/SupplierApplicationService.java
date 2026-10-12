package mss.mooncake.identity.service;

import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.dto.response.SupplierSummaryResponse;

public interface SupplierApplicationService {
    SupplierProfileResponse registerSupplier(Long userId, SupplierProfileRequest request);
    SupplierProfileResponse getSupplierProfile(Long userId);
    SupplierProfileResponse updateSupplierProfile(Long userId, UpdateSupplierProfileRequest request);
    SupplierSummaryResponse getSupplierSummary(Long supplierId);
    SupplierSummaryResponse getSupplierSummaryByUserId(Long userId);
}
