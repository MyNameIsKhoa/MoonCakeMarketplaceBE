package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import feign.FeignException;
import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.client.IdentityClient;
import mss.mooncake.catalogstore.client.dto.SupplierSummaryDto;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.StoreRepository;
import mss.mooncake.catalogstore.service.impl.StoreAccessServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreAccessServiceImpl Unit Tests")
class StoreAccessServiceImplTest {

    @Mock private StoreRepository storeRepository;
    @Mock private IdentityClient identityClient;

    @InjectMocks
    private StoreAccessServiceImpl storeAccessService;

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long STORE_ID = 1L;
    private static final Long OWNER_USER = 10L;
    private static final Long OTHER_USER = 20L;
    private static final Long OWNER_SUPPLIER = 100L;
    private static final Long OTHER_SUPPLIER = 200L;

    private static StoreEntity store(StoreStatus status) {
        StoreEntity s = new StoreEntity();
        ReflectionTestUtils.setField(s, "id", STORE_ID);
        s.setSupplierIdRef(OWNER_SUPPLIER);
        s.setStatus(status);
        return s;
    }

    private static SupplierSummaryDto supplier(Long supplierId, Long userId, String verificationStatus) {
        return new SupplierSummaryDto(supplierId, userId, "Công ty", "TAX-" + supplierId, verificationStatus);
    }

    @Nested
    @DisplayName("requireOwnedStore")
    class RequireOwnedStore {

        @Test
        @DisplayName("supplier VERIFIED sở hữu store → trả store")
        void verifiedOwner() {
            StoreEntity s = store(StoreStatus.ACTIVE);
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(s));
            when(identityClient.getSupplierSummaryByUserId(OWNER_USER))
                    .thenReturn(supplier(OWNER_SUPPLIER, OWNER_USER, "VERIFIED"));

            assertThat(storeAccessService.requireOwnedStore(STORE_ID, OWNER_USER)).isSameAs(s);
        }

        @Test
        @DisplayName("store không tồn tại → 404, không gọi Identity")
        void storeNotFound() {
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> storeAccessService.requireOwnedStore(STORE_ID, OWNER_USER))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(identityClient, never()).getSupplierSummaryByUserId(any());
        }

        @Test
        @DisplayName("supplier PENDING/REJECTED/SUSPENDED → 400")
        void supplierNotVerified() {
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store(StoreStatus.ACTIVE)));
            for (String status : List.of("PENDING", "REJECTED", "SUSPENDED")) {
                when(identityClient.getSupplierSummaryByUserId(OWNER_USER))
                        .thenReturn(supplier(OWNER_SUPPLIER, OWNER_USER, status));

                assertThatThrownBy(() -> storeAccessService.requireOwnedStore(STORE_ID, OWNER_USER))
                        .as("status %s", status)
                        .isInstanceOf(BadRequestException.class);
            }
        }

        @Test
        @DisplayName("user chưa có hồ sơ supplier (Identity 404) → 400")
        void noSupplierProfile() {
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store(StoreStatus.ACTIVE)));
            when(identityClient.getSupplierSummaryByUserId(OTHER_USER)).thenThrow(FeignException.NotFound.class);

            assertThatThrownBy(() -> storeAccessService.requireOwnedStore(STORE_ID, OTHER_USER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("supplier VERIFIED nhưng không sở hữu store → 403")
        void notOwner() {
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store(StoreStatus.ACTIVE)));
            when(identityClient.getSupplierSummaryByUserId(OTHER_USER))
                    .thenReturn(supplier(OTHER_SUPPLIER, OTHER_USER, "VERIFIED"));

            assertThatThrownBy(() -> storeAccessService.requireOwnedStore(STORE_ID, OTHER_USER))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("store SUSPENDED → 403 kể cả với owner")
        void suspendedStore() {
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store(StoreStatus.SUSPENDED)));
            when(identityClient.getSupplierSummaryByUserId(OWNER_USER))
                    .thenReturn(supplier(OWNER_SUPPLIER, OWNER_USER, "VERIFIED"));

            assertThatThrownBy(() -> storeAccessService.requireOwnedStore(STORE_ID, OWNER_USER))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("tạm khóa");
        }

        @Test
        @DisplayName("Identity lỗi khác 404 (ví dụ 500) → lỗi hệ thống, không coi là không phải supplier")
        void identityUnavailable() {
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store(StoreStatus.ACTIVE)));
            when(identityClient.getSupplierSummaryByUserId(OWNER_USER))
                    .thenThrow(FeignException.InternalServerError.class);

            assertThatThrownBy(() -> storeAccessService.requireOwnedStore(STORE_ID, OWNER_USER))
                    .isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Identity");
        }
    }

    @Nested
    @DisplayName("findOwnedStoreIds / isOwner / isPublic")
    class Queries {

        @Test
        @DisplayName("findOwnedStoreIds: trả ID store của supplier; chưa là supplier → rỗng")
        void ownedStoreIds() {
            when(identityClient.getSupplierSummaryByUserId(OWNER_USER))
                    .thenReturn(supplier(OWNER_SUPPLIER, OWNER_USER, "PENDING"));
            when(storeRepository.findBySupplierIdRef(OWNER_SUPPLIER)).thenReturn(List.of(store(StoreStatus.DRAFT)));
            when(identityClient.getSupplierSummaryByUserId(OTHER_USER)).thenThrow(FeignException.NotFound.class);

            assertThat(storeAccessService.findOwnedStoreIds(OWNER_USER)).containsExactly(STORE_ID);
            assertThat(storeAccessService.findOwnedStoreIds(OTHER_USER)).isEmpty();
        }

        @Test
        @DisplayName("isOwner: đúng supplier → true; supplier khác, chưa là supplier, userId null → false")
        void isOwner() {
            when(identityClient.getSupplierSummaryByUserId(OWNER_USER))
                    .thenReturn(supplier(OWNER_SUPPLIER, OWNER_USER, "VERIFIED"));
            when(identityClient.getSupplierSummaryByUserId(OTHER_USER))
                    .thenReturn(supplier(OTHER_SUPPLIER, OTHER_USER, "VERIFIED"));
            when(storeRepository.findById(STORE_ID)).thenReturn(Optional.of(store(StoreStatus.DRAFT)));

            assertThat(storeAccessService.isOwner(STORE_ID, OWNER_USER)).isTrue();
            assertThat(storeAccessService.isOwner(STORE_ID, OTHER_USER)).isFalse();
            assertThat(storeAccessService.isOwner(STORE_ID, null)).isFalse();
        }

        @Test
        @DisplayName("isPublic: chỉ store ACTIVE")
        void isPublic() {
            when(storeRepository.findByIdAndStatus(STORE_ID, StoreStatus.ACTIVE)).thenReturn(Optional.of(store(StoreStatus.ACTIVE)));
            when(storeRepository.findByIdAndStatus(2L, StoreStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThat(storeAccessService.isPublic(STORE_ID)).isTrue();
            assertThat(storeAccessService.isPublic(2L)).isFalse();
        }
    }
}
