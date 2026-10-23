package mss.mooncake.catalogstore.service.impl;

import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.response.StoreBranchResponse;
import mss.mooncake.catalogstore.entity.StoreBranchEntity;
import mss.mooncake.catalogstore.enums.StoreBranchStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.StoreBranchRepository;
import mss.mooncake.catalogstore.service.StoreAccessService;
import mss.mooncake.catalogstore.service.StoreBranchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class StoreBranchServiceImpl implements StoreBranchService {

    private final StoreBranchRepository branchRepository;
    private final StoreAccessService storeAccessService;

    public StoreBranchServiceImpl(StoreBranchRepository branchRepository, StoreAccessService storeAccessService) {
        this.branchRepository = branchRepository;
        this.storeAccessService = storeAccessService;
    }

    // ─── Helpers
    // ──────────────────────────────────────────────────────────────────

    /** Map entity → StoreBranchResponse. */
    private StoreBranchResponse toResponse(StoreBranchEntity b) {
        return new StoreBranchResponse(b.getId(), b.getStoreId(), b.getName(), b.getAddressLine(), b.getWard(),
                b.getDistrict(), b.getCity(), b.getPhone(), b.getOpeningHours(), b.isPrimary(), b.getStatus().name());
    }

    private StoreBranchEntity requireBranch(Long storeId, Long branchId) {
        return branchRepository.findByIdAndStoreId(branchId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy chi nhánh " + branchId + " của store " + storeId));
    }

    private StoreBranchStatus parseStatus(String status) {
        try {
            return StoreBranchStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Trạng thái chi nhánh không hợp lệ: " + status);
        }
    }

    // ─── Public API
    // ───────────────────────────────────────────────────────────────

    @Override
    public List<StoreBranchResponse> getBranches(Long storeId, Long userId) {
        List<StoreBranchEntity> branches;
        if (userId != null && storeAccessService.isOwner(storeId, userId)) {
            branches = branchRepository.findByStoreIdOrderByPrimaryDescIdAsc(storeId);
        } else if (storeAccessService.isPublic(storeId)) {
            branches = branchRepository.findByStoreIdAndStatusOrderByPrimaryDescIdAsc(storeId,
                    StoreBranchStatus.ACTIVE);
        } else {
            throw new ResourceNotFoundException("Không tìm thấy store với id: " + storeId);
        }
        return branches.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public StoreBranchResponse createBranch(Long storeId, CreateStoreBranchRequest request, Long userId) {
        storeAccessService.requireOwnedStore(storeId, userId);

        boolean first = branchRepository.findByStoreIdOrderByPrimaryDescIdAsc(storeId).isEmpty();
        boolean primary = first || Boolean.TRUE.equals(request.primary());
        if (primary && !first) {
            branchRepository.clearPrimary(storeId);
        }

        StoreBranchEntity branch = new StoreBranchEntity();
        branch.setStoreId(storeId);
        branch.setName(request.name());
        branch.setAddressLine(request.addressLine());
        branch.setWard(request.ward());
        branch.setDistrict(request.district());
        branch.setCity(request.city());
        branch.setPhone(request.phone());
        branch.setOpeningHours(request.openingHours());
        branch.setPrimary(primary);
        branch.setStatus(StoreBranchStatus.ACTIVE);
        return toResponse(branchRepository.save(branch));
    }

    @Override
    @Transactional
    public StoreBranchResponse updateBranch(Long storeId, Long branchId, UpdateStoreBranchRequest request,
            Long userId) {
        storeAccessService.requireOwnedStore(storeId, userId);

        // clearPrimary xóa persistence context, nên phải chạy trước khi load branch.
        if (Boolean.TRUE.equals(request.primary())) {
            branchRepository.clearPrimary(storeId);
        }
        StoreBranchEntity branch = requireBranch(storeId, branchId);

        if (StringUtils.hasText(request.name())) {
            branch.setName(request.name());
        }
        if (StringUtils.hasText(request.addressLine())) {
            branch.setAddressLine(request.addressLine());
        }
        if (request.ward() != null) {
            branch.setWard(request.ward());
        }
        if (request.district() != null) {
            branch.setDistrict(request.district());
        }
        if (StringUtils.hasText(request.city())) {
            branch.setCity(request.city());
        }
        if (request.phone() != null) {
            branch.setPhone(request.phone());
        }
        if (request.openingHours() != null) {
            branch.setOpeningHours(request.openingHours());
        }
        if (request.primary() != null) {
            branch.setPrimary(request.primary());
        }
        if (StringUtils.hasText(request.status())) {
            branch.setStatus(parseStatus(request.status()));
        }
        return toResponse(branchRepository.save(branch));
    }

    @Override
    @Transactional
    public void deleteBranch(Long storeId, Long branchId, Long userId) {
        storeAccessService.requireOwnedStore(storeId, userId);
        branchRepository.delete(requireBranch(storeId, branchId));
    }
}
