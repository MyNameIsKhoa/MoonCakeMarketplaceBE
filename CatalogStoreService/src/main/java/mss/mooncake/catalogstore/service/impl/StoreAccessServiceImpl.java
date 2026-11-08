package mss.mooncake.catalogstore.service.impl;

import feign.FeignException;
import java.util.List;
import mss.mooncake.catalogstore.client.IdentityClient;
import mss.mooncake.catalogstore.client.dto.SupplierSummaryDto;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.StoreRepository;
import mss.mooncake.catalogstore.service.StoreAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StoreAccessServiceImpl implements StoreAccessService {

    private final StoreRepository storeRepository;
    private final IdentityClient identityClient;

    public StoreAccessServiceImpl(StoreRepository storeRepository, IdentityClient identityClient) {
        this.storeRepository = storeRepository;
        this.identityClient = identityClient;
    }

    // ─── Helpers
    // ──────────────────────────────────────────────────────────────────

    /** SupplierSummary của userId, hoặc null nếu user chưa có hồ sơ supplier. */
    private SupplierSummaryDto findSupplier(Long userId) {
        if (userId == null)
            return null;
        try {
            return identityClient.getSupplierSummaryByUserId(userId);
        } catch (FeignException.NotFound e) {
            return null;
        } catch (FeignException e) {
            throw new RuntimeException("Không thể kết nối Identity Service để xác minh supplier: " + e.getMessage(), e);
        }
    }

    private static boolean currentUserIsAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // ─── Public API
    // ───────────────────────────────────────────────────────────────

    @Override
    public StoreEntity requireOwnedStore(Long storeId, Long userId) {
        StoreEntity store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy store với id: " + storeId));

        SupplierSummaryDto supplier = findSupplier(userId);
        if (supplier == null && currentUserIsAdmin()) {
            // Admin qua được SecurityConfig nhưng không phải chủ store: báo 403 rõ ràng thay vì 400 "chưa xác minh".
            throw new ForbiddenException("Quản trị viên không chỉnh sửa trực tiếp catalog của nhà cung cấp. "
                    + "Dùng chức năng tạm khóa/mở lại store trong trang quản trị nếu cần can thiệp.");
        }
        if (supplier == null || !"VERIFIED".equalsIgnoreCase(supplier.verificationStatus())) {
            throw new BadRequestException("Hồ sơ nhà cung cấp chưa được xác minh. " +
                    "Vui lòng chờ phê duyệt trước khi quản lý sản phẩm hoặc khuyến mãi.");
        }
        if (!store.getSupplierIdRef().equals(supplier.supplierId())) {
            throw new ForbiddenException("Bạn không có quyền thao tác trên store này.");
        }
        if (store.getStatus() == StoreStatus.SUSPENDED) {
            throw new ForbiddenException("Store đang bị tạm khóa, không thể chỉnh sửa catalog.");
        }
        return store;
    }

    @Override
    public List<Long> findOwnedStoreIds(Long userId) {
        SupplierSummaryDto supplier = findSupplier(userId);
        if (supplier == null)
            return List.of();
        return storeRepository.findBySupplierIdRef(supplier.supplierId())
                .stream()
                .map(StoreEntity::getId)
                .toList();
    }

    @Override
    public boolean isOwner(Long storeId, Long userId) {
        SupplierSummaryDto supplier = findSupplier(userId);
        if (supplier == null)
            return false;
        return storeRepository.findById(storeId)
                .map(store -> store.getSupplierIdRef().equals(supplier.supplierId()))
                .orElse(false);
    }

    @Override
    public boolean isPublic(Long storeId) {
        return storeRepository.findByIdAndStatus(storeId, StoreStatus.ACTIVE).isPresent();
    }
}
