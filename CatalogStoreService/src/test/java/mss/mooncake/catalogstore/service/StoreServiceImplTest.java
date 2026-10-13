package mss.mooncake.catalogstore.service;

import feign.FeignException;
import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.client.IdentityClient;
import mss.mooncake.catalogstore.client.dto.SupplierSummaryDto;
import mss.mooncake.catalogstore.dto.request.CreateStoreRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreRequest;
import mss.mooncake.catalogstore.dto.response.StoreResponse;
import mss.mooncake.catalogstore.dto.response.StoreSummaryResponse;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.StoreRepository;
import mss.mooncake.catalogstore.service.impl.StoreServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreServiceImpl Unit Tests")
class StoreServiceImplTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private IdentityClient identityClient;

    @InjectMocks
    private StoreServiceImpl storeService;

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long USER_A = 10L;
    private static final Long USER_B = 20L;
    private static final Long SUPPLIER_A_ID = 100L;
    private static final Long SUPPLIER_B_ID = 200L;

    private SupplierSummaryDto supplierA;
    private SupplierSummaryDto supplierB;
    private StoreEntity storeOfSupplierA;

    @BeforeEach
    void setUp() {
        supplierA = new SupplierSummaryDto(SUPPLIER_A_ID, USER_A, "Công ty A", "TAX-001", "VERIFIED");
        supplierB = new SupplierSummaryDto(SUPPLIER_B_ID, USER_B, "Công ty B", "TAX-002", "VERIFIED");

        storeOfSupplierA = buildStore(1L, SUPPLIER_A_ID, "Store A", "store-a", "STR-AAAAAAAA");
    }

    private StoreEntity buildStore(Long id, Long supplierIdRef, String name, String slug, String code) {
        StoreEntity e = new StoreEntity();
        // set id via reflection since BaseCatalogEntity.id is protected
        try {
            var field = mss.mooncake.catalogstore.entity.BaseCatalogEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(e, id);
        } catch (Exception ex) { throw new RuntimeException(ex); }
        e.setSupplierIdRef(supplierIdRef);
        e.setName(name);
        e.setSlug(slug);
        e.setStoreCode(code);
        e.setStatus(StoreStatus.DRAFT);
        return e;
    }

    // ── POST /api/stores ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createStore")
    class CreateStore {

        @Test
        @DisplayName("Tạo store thành công khi user là supplier hợp lệ")
        void createStore_success() {
            when(identityClient.getSupplierSummaryByUserId(USER_A)).thenReturn(supplierA);
            when(storeRepository.existsByStoreCode(anyString())).thenReturn(false);
            when(storeRepository.existsBySlug(anyString())).thenReturn(false);
            when(storeRepository.save(any(StoreEntity.class))).thenAnswer(inv -> {
                StoreEntity s = inv.getArgument(0);
                try {
                    var f = mss.mooncake.catalogstore.entity.BaseCatalogEntity.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(s, 99L);
                } catch (Exception e) { throw new RuntimeException(e); }
                return s;
            });

            CreateStoreRequest req = new CreateStoreRequest("My Store", null, null, null);
            StoreResponse resp = storeService.createStore(req, USER_A);

            assertThat(resp.name()).isEqualTo("My Store");
            assertThat(resp.supplierIdRef()).isEqualTo(SUPPLIER_A_ID);
            assertThat(resp.storeCode()).startsWith("STR-");
            assertThat(resp.status()).isEqualTo("DRAFT");
            verify(storeRepository, times(1)).save(any(StoreEntity.class));
        }

        @Test
        @DisplayName("Ném BadRequestException khi user chưa đăng ký supplier")
        void createStore_userNotSupplier_throwsBadRequest() {
            when(identityClient.getSupplierSummaryByUserId(USER_A))
                    .thenThrow(FeignException.NotFound.class);

            CreateStoreRequest req = new CreateStoreRequest("My Store", null, null, null);

            assertThatThrownBy(() -> storeService.createStore(req, USER_A))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("supplier");

            verify(storeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ném BadRequestException khi supplier chưa VERIFIED/ACTIVE")
        void createStore_supplierNotVerified_throwsBadRequest() {
            SupplierSummaryDto pendingSupplier = new SupplierSummaryDto(SUPPLIER_A_ID, USER_A, "Công ty A", "TAX-001", "PENDING");
            when(identityClient.getSupplierSummaryByUserId(USER_A)).thenReturn(pendingSupplier);

            CreateStoreRequest req = new CreateStoreRequest("My Store", null, null, null);

            assertThatThrownBy(() -> storeService.createStore(req, USER_A))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được xác minh");

            verify(storeRepository, never()).save(any());
        }
    }

    // ── GET /api/stores ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMyStores")
    class GetMyStores {

        @Test
        @DisplayName("Chỉ trả store của supplier đang login")
        void getMyStores_returnsOnlyOwnStores() {
            StoreEntity storeOfB = buildStore(2L, SUPPLIER_B_ID, "Store B", "store-b", "STR-BBBBBBBB");

            when(identityClient.getSupplierSummaryByUserId(USER_A)).thenReturn(supplierA);
            when(storeRepository.findBySupplierIdRef(SUPPLIER_A_ID))
                    .thenReturn(List.of(storeOfSupplierA)); // chỉ store A, không có store B

            List<StoreResponse> result = storeService.getMyStores(USER_A);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).supplierIdRef()).isEqualTo(SUPPLIER_A_ID);
            // storeB không lọt vào
            assertThat(result.stream().anyMatch(r -> r.supplierIdRef().equals(SUPPLIER_B_ID))).isFalse();
        }
    }

    // ── GET /api/stores/{id} ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStoreById")
    class GetStoreById {

        @Test
        @DisplayName("Trả store khi tìm thấy")
        void getStoreById_found() {
            storeOfSupplierA.setStatus(StoreStatus.ACTIVE);
            when(storeRepository.findByIdAndStatus(1L, StoreStatus.ACTIVE)).thenReturn(Optional.of(storeOfSupplierA));
            StoreResponse resp = storeService.getStoreById(1L);
            assertThat(resp.storeId()).isEqualTo(1L);
            assertThat(resp.name()).isEqualTo("Store A");
        }

        @Test
        @DisplayName("Ném ResourceNotFoundException khi không tìm thấy")
        void getStoreById_notFound() {
            when(storeRepository.findByIdAndStatus(999L, StoreStatus.ACTIVE)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> storeService.getStoreById(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── PATCH /api/stores/{id} ────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateStore — ownership enforcement")
    class UpdateStore {

        @Test
        @DisplayName("Cập nhật thành công khi user là owner store")
        void updateStore_ownerSuccess() {
            when(storeRepository.findById(1L)).thenReturn(Optional.of(storeOfSupplierA));
            when(identityClient.getSupplierSummaryByUserId(USER_A)).thenReturn(supplierA);
            when(storeRepository.save(any())).thenReturn(storeOfSupplierA);

            UpdateStoreRequest req = new UpdateStoreRequest("Store A Updated", null, null, "dark", null);
            StoreResponse resp = storeService.updateStore(1L, req, USER_A);

            assertThat(resp).isNotNull();
            verify(storeRepository).save(any(StoreEntity.class));
        }

        @Test
        @DisplayName("Ném ForbiddenException khi Supplier B cố sửa store của Supplier A")
        void updateStore_notOwner_throwsForbidden() {
            // store thuộc Supplier A
            when(storeRepository.findById(1L)).thenReturn(Optional.of(storeOfSupplierA));
            // nhưng user đang login là USER_B → supplierId = SUPPLIER_B_ID
            when(identityClient.getSupplierSummaryByUserId(USER_B)).thenReturn(supplierB);

            UpdateStoreRequest req = new UpdateStoreRequest("Hacked Name", null, null, null, null);

            // USER_B không thể sửa store của SUPPLIER_A
            assertThatThrownBy(() -> storeService.updateStore(1L, req, USER_B))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("quyền");

            verify(storeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ném ResourceNotFoundException khi store không tồn tại")
        void updateStore_storeNotFound() {
            when(storeRepository.findById(999L)).thenReturn(Optional.empty());
            UpdateStoreRequest req = new UpdateStoreRequest(null, null, null, null, null);

            assertThatThrownBy(() -> storeService.updateStore(999L, req, USER_A))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Ném BadRequestException khi USER_B không có hồ sơ supplier")
        void updateStore_userNotSupplier_throwsBadRequest() {
            when(storeRepository.findById(1L)).thenReturn(Optional.of(storeOfSupplierA));
            when(identityClient.getSupplierSummaryByUserId(USER_B))
                    .thenThrow(FeignException.NotFound.class);

            UpdateStoreRequest req = new UpdateStoreRequest(null, null, null, null, null);

            assertThatThrownBy(() -> storeService.updateStore(1L, req, USER_B))
                    .isInstanceOf(BadRequestException.class);

            verify(storeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Ném BadRequestException khi supplier chưa VERIFIED/ACTIVE")
        void updateStore_supplierNotActive_throwsBadRequest() {
            SupplierSummaryDto pendingSupplier = new SupplierSummaryDto(SUPPLIER_A_ID, USER_A, "Công ty A", "TAX-001", "PENDING");
            when(storeRepository.findById(1L)).thenReturn(Optional.of(storeOfSupplierA));
            when(identityClient.getSupplierSummaryByUserId(USER_A)).thenReturn(pendingSupplier);

            UpdateStoreRequest req = new UpdateStoreRequest("Updated", null, null, null, null);

            assertThatThrownBy(() -> storeService.updateStore(1L, req, USER_A))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("chưa được xác minh");

            verify(storeRepository, never()).save(any());
        }
    }

    // ── GET /api/stores/{idOrSlug} & by supplier ──────────────────────────────────

    @Nested
    @DisplayName("getStoreByIdOrSlug & getStoresBySupplierId")
    class SearchAndFilterStores {

        @Test
        @DisplayName("getStoreByIdOrSlug tìm thấy theo numeric ID")
        void getStoreByIdOrSlug_foundById() {
            storeOfSupplierA.setStatus(StoreStatus.ACTIVE);
            when(storeRepository.findByIdAndStatus(1L, StoreStatus.ACTIVE)).thenReturn(Optional.of(storeOfSupplierA));

            StoreResponse resp = storeService.getStoreByIdOrSlug("1");

            assertThat(resp.storeId()).isEqualTo(1L);
            assertThat(resp.name()).isEqualTo("Store A");
        }

        @Test
        @DisplayName("getStoreByIdOrSlug tìm thấy theo slug chuỗi")
        void getStoreByIdOrSlug_foundBySlug() {
            storeOfSupplierA.setStatus(StoreStatus.ACTIVE);
            when(storeRepository.findBySlugAndStatus("store-a", StoreStatus.ACTIVE)).thenReturn(Optional.of(storeOfSupplierA));

            StoreResponse resp = storeService.getStoreByIdOrSlug("store-a");

            assertThat(resp.slug()).isEqualTo("store-a");
            assertThat(resp.name()).isEqualTo("Store A");
        }

        @Test
        @DisplayName("getStoresBySupplierId trả danh sách theo supplierId")
        void getStoresBySupplierId_returnsStores() {
            storeOfSupplierA.setStatus(StoreStatus.ACTIVE);
            when(storeRepository.findBySupplierIdRefAndStatusOrderByIdAsc(SUPPLIER_A_ID, StoreStatus.ACTIVE))
                    .thenReturn(List.of(storeOfSupplierA));

            List<StoreResponse> list = storeService.getStoresBySupplierId(SUPPLIER_A_ID);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).supplierIdRef()).isEqualTo(SUPPLIER_A_ID);
        }
    }

    // ── Internal summary ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStoreSummary — internal contract")
    class GetStoreSummary {

        @Test
        @DisplayName("Trả đúng summary fields cho TV3/TV4 snapshot")
        void getStoreSummary_returnsCorrectFields() {
            when(storeRepository.findById(1L)).thenReturn(Optional.of(storeOfSupplierA));

            StoreSummaryResponse summary = storeService.getStoreSummary(1L);

            assertThat(summary.storeId()).isEqualTo(1L);
            assertThat(summary.supplierIdRef()).isEqualTo(SUPPLIER_A_ID);
            assertThat(summary.storeCode()).isEqualTo("STR-AAAAAAAA");
            assertThat(summary.name()).isEqualTo("Store A");
            assertThat(summary.slug()).isEqualTo("store-a");
            assertThat(summary.status()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("Ném ResourceNotFoundException khi store không tồn tại")
        void getStoreSummary_notFound() {
            when(storeRepository.findById(anyLong())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> storeService.getStoreSummary(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
