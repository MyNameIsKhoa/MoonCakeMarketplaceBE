package mss.mooncake.catalogstore.service;

import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.response.StoreBranchResponse;

public interface StoreBranchService {

    /**
     * Danh sách chi nhánh của store, chi nhánh chính lên đầu.
     * Người ngoài chỉ thấy chi nhánh ACTIVE của store ACTIVE; owner thấy mọi trạng
     * thái.
     *
     * @param userId userId từ JWT principal, null nếu chưa đăng nhập
     */
    List<StoreBranchResponse> getBranches(Long storeId, Long userId);

    /** Tạo chi nhánh; chi nhánh đầu tiên của store tự động là chi nhánh chính. */
    StoreBranchResponse createBranch(Long storeId, CreateStoreBranchRequest request, Long userId);

    StoreBranchResponse updateBranch(Long storeId, Long branchId, UpdateStoreBranchRequest request, Long userId);

    void deleteBranch(Long storeId, Long branchId, Long userId);
}
