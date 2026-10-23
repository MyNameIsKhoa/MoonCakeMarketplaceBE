package mss.mooncake.catalogstore.service;

import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.dto.request.CreateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreBranchRequest;
import mss.mooncake.catalogstore.dto.response.StoreBranchResponse;
import mss.mooncake.catalogstore.entity.StoreBranchEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.StoreBranchStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.StoreBranchRepository;
import mss.mooncake.catalogstore.service.impl.StoreBranchServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreBranchServiceImpl Unit Tests")
class StoreBranchServiceImplTest {

    @Mock
    private StoreBranchRepository branchRepository;

    @Mock
    private StoreAccessService storeAccessService;

    @InjectMocks
    private StoreBranchServiceImpl branchService;

    private static final Long STORE_ID = 1L;
    private static final Long OWNER = 10L;
    private static final Long STRANGER = 20L;

    private StoreBranchEntity buildBranch(Long id, boolean primary, StoreBranchStatus status) {
        StoreBranchEntity b = new StoreBranchEntity();
        ReflectionTestUtils.setField(b, "id", id);
        b.setStoreId(STORE_ID);
        b.setName("Chi nhánh " + id);
        b.setAddressLine("1 Lê Lợi");
        b.setCity("TP.HCM");
        b.setPrimary(primary);
        b.setStatus(status);
        return b;
    }

    private CreateStoreBranchRequest createRequest(Boolean primary) {
        return new CreateStoreBranchRequest("Chi nhánh Quận 1", "1 Lê Lợi", "Bến Nghé", "Quận 1", "TP.HCM",
                "0901234567", "08:00–21:00", primary);
    }

    private void saveReturnsArgument() {
        when(branchRepository.save(any(StoreBranchEntity.class))).thenAnswer(inv -> {
            StoreBranchEntity b = inv.getArgument(0);
            if (b.getId() == null)
                ReflectionTestUtils.setField(b, "id", 99L);
            return b;
        });
    }

    @Nested
    @DisplayName("getBranches")
    class GetBranches {

        @Test
        @DisplayName("Người ngoài chỉ thấy chi nhánh ACTIVE của store công khai")
        void public_seesActiveOnly() {
            when(storeAccessService.isPublic(STORE_ID)).thenReturn(true);
            when(branchRepository.findByStoreIdAndStatusOrderByPrimaryDescIdAsc(STORE_ID, StoreBranchStatus.ACTIVE))
                    .thenReturn(List.of(buildBranch(1L, true, StoreBranchStatus.ACTIVE)));

            List<StoreBranchResponse> result = branchService.getBranches(STORE_ID, null);

            assertThat(result).hasSize(1);
            verify(branchRepository, never()).findByStoreIdOrderByPrimaryDescIdAsc(STORE_ID);
        }

        @Test
        @DisplayName("Owner thấy mọi trạng thái")
        void owner_seesAll() {
            when(storeAccessService.isOwner(STORE_ID, OWNER)).thenReturn(true);
            when(branchRepository.findByStoreIdOrderByPrimaryDescIdAsc(STORE_ID)).thenReturn(List.of(
                    buildBranch(1L, true, StoreBranchStatus.ACTIVE),
                    buildBranch(2L, false, StoreBranchStatus.INACTIVE)));

            assertThat(branchService.getBranches(STORE_ID, OWNER)).hasSize(2);
        }

        @Test
        @DisplayName("Store chưa công khai → 404 với người ngoài")
        void nonPublicStore_notFound() {
            when(storeAccessService.isOwner(STORE_ID, STRANGER)).thenReturn(false);
            when(storeAccessService.isPublic(STORE_ID)).thenReturn(false);

            assertThatThrownBy(() -> branchService.getBranches(STORE_ID, STRANGER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("createBranch")
    class CreateBranch {

        @Test
        @DisplayName("Chi nhánh đầu tiên tự động là chi nhánh chính")
        void firstBranch_isPrimary() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(branchRepository.findByStoreIdOrderByPrimaryDescIdAsc(STORE_ID)).thenReturn(List.of());
            saveReturnsArgument();

            StoreBranchResponse result = branchService.createBranch(STORE_ID, createRequest(null), OWNER);

            assertThat(result.primary()).isTrue();
            assertThat(result.status()).isEqualTo("ACTIVE");
            assertThat(result.district()).isEqualTo("Quận 1");
            verify(branchRepository, never()).clearPrimary(STORE_ID);
        }

        @Test
        @DisplayName("Tạo chi nhánh chính mới → bỏ cờ chính của chi nhánh cũ")
        void newPrimary_clearsOldPrimary() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(branchRepository.findByStoreIdOrderByPrimaryDescIdAsc(STORE_ID))
                    .thenReturn(List.of(buildBranch(1L, true, StoreBranchStatus.ACTIVE)));
            saveReturnsArgument();

            StoreBranchResponse result = branchService.createBranch(STORE_ID, createRequest(true), OWNER);

            assertThat(result.primary()).isTrue();
            verify(branchRepository).clearPrimary(STORE_ID);
        }

        @Test
        @DisplayName("Không phải owner → không lưu gì")
        void notOwner_forbidden() {
            when(storeAccessService.requireOwnedStore(STORE_ID, STRANGER))
                    .thenThrow(new ForbiddenException("forbidden"));

            assertThatThrownBy(() -> branchService.createBranch(STORE_ID, createRequest(null), STRANGER))
                    .isInstanceOf(ForbiddenException.class);
            verify(branchRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateBranch")
    class UpdateBranch {

        @Test
        @DisplayName("Đặt làm chi nhánh chính: clearPrimary chạy trước khi load branch")
        void setPrimary_clearsBeforeLoad() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(branchRepository.findByIdAndStoreId(2L, STORE_ID))
                    .thenReturn(Optional.of(buildBranch(2L, false, StoreBranchStatus.ACTIVE)));
            saveReturnsArgument();

            StoreBranchResponse result = branchService.updateBranch(STORE_ID, 2L,
                    new UpdateStoreBranchRequest(null, null, null, null, null, null, null, true, null), OWNER);

            assertThat(result.primary()).isTrue();
            InOrder order = inOrder(branchRepository);
            order.verify(branchRepository).clearPrimary(STORE_ID);
            order.verify(branchRepository).findByIdAndStoreId(2L, STORE_ID);
        }

        @Test
        @DisplayName("Patch field và status")
        void patchFields() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(branchRepository.findByIdAndStoreId(1L, STORE_ID))
                    .thenReturn(Optional.of(buildBranch(1L, true, StoreBranchStatus.ACTIVE)));
            saveReturnsArgument();

            StoreBranchResponse result = branchService.updateBranch(STORE_ID, 1L,
                    new UpdateStoreBranchRequest("Chi nhánh mới", null, null, null, null, "0281234567", null, null,
                            "inactive"),
                    OWNER);

            assertThat(result.name()).isEqualTo("Chi nhánh mới");
            assertThat(result.phone()).isEqualTo("0281234567");
            assertThat(result.addressLine()).isEqualTo("1 Lê Lợi");
            assertThat(result.status()).isEqualTo("INACTIVE");
            verify(branchRepository, never()).clearPrimary(STORE_ID);
        }

        @Test
        @DisplayName("Status không hợp lệ → 400")
        void invalidStatus_badRequest() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(branchRepository.findByIdAndStoreId(1L, STORE_ID))
                    .thenReturn(Optional.of(buildBranch(1L, true, StoreBranchStatus.ACTIVE)));

            assertThatThrownBy(() -> branchService.updateBranch(STORE_ID, 1L,
                    new UpdateStoreBranchRequest(null, null, null, null, null, null, null, null, "CLOSED"), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("Chi nhánh thuộc store khác → 404")
        void branchOfOtherStore_notFound() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(branchRepository.findByIdAndStoreId(5L, STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> branchService.updateBranch(STORE_ID, 5L,
                    new UpdateStoreBranchRequest("x", null, null, null, null, null, null, null, null), OWNER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("deleteBranch: owner xóa được chi nhánh của store mình")
    void deleteBranch_success() {
        StoreBranchEntity branch = buildBranch(1L, true, StoreBranchStatus.ACTIVE);
        when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
        when(branchRepository.findByIdAndStoreId(1L, STORE_ID)).thenReturn(Optional.of(branch));

        branchService.deleteBranch(STORE_ID, 1L, OWNER);

        verify(branchRepository).delete(branch);
    }
}
