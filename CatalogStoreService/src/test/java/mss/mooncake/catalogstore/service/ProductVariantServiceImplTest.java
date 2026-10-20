package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.dto.request.CreateVariantRequest;
import mss.mooncake.catalogstore.dto.request.UpdateVariantRequest;
import mss.mooncake.catalogstore.dto.response.VariantResponse;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.service.impl.ProductVariantServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductVariantServiceImpl Unit Tests")
class ProductVariantServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private StoreAccessService storeAccessService;

    @InjectMocks
    private ProductVariantServiceImpl variantService;

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long STORE_ID = 1L;
    private static final Long PRODUCT_ID = 5L;
    private static final Long OWNER = 10L;
    private static final Long STRANGER = 20L;

    private static ProductEntity product(ProductStatus status) {
        ProductEntity p = new ProductEntity();
        ReflectionTestUtils.setField(p, "id", PRODUCT_ID);
        p.setStoreId(STORE_ID);
        p.setStatus(status);
        return p;
    }

    private static ProductVariantEntity variant(Long id, ProductStatus status) {
        ProductVariantEntity v = new ProductVariantEntity();
        ReflectionTestUtils.setField(v, "id", id);
        v.setProductId(PRODUCT_ID);
        v.setSku("SKU-" + id);
        v.setVariantName("Hộp " + id);
        v.setBasePrice(new BigDecimal("100000"));
        v.setStockQuantity(10);
        v.setStatus(status);
        return v;
    }

    private static CreateVariantRequest createRequest(String sku) {
        return new CreateVariantRequest(sku, "Hộp 4 bánh", null, new BigDecimal("250000"), 20);
    }

    private static UpdateVariantRequest statusOnly(String status) {
        return new UpdateVariantRequest(null, null, null, null, null, status);
    }

    @Nested
    @DisplayName("createVariant — CA13")
    class Create {

        @Test
        @DisplayName("tạo variant DRAFT khi là owner")
        void createDraft() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(variantRepository.existsBySku("SKU-NEW")).thenReturn(false);
            when(variantRepository.saveAndFlush(any(ProductVariantEntity.class))).thenAnswer(inv -> {
                ProductVariantEntity saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 7L);
                return saved;
            });

            VariantResponse response = variantService.createVariant(PRODUCT_ID, createRequest(" SKU-NEW "), OWNER);

            assertThat(response.variantId()).isEqualTo(7L);
            assertThat(response.sku()).isEqualTo("SKU-NEW");
            assertThat(response.status()).isEqualTo("DRAFT");
            assertThat(response.basePrice()).isEqualByComparingTo("250000");
        }

        @Test
        @DisplayName("SKU trùng → 409 (kiểm tra trước và cả khi DB báo unique)")
        void duplicateSku() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(variantRepository.existsBySku("SKU-1")).thenReturn(true);
            when(variantRepository.existsBySku("SKU-RACE")).thenReturn(false);
            when(variantRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("UQ_product_variant_sku"));

            assertThatThrownBy(() -> variantService.createVariant(PRODUCT_ID, createRequest("SKU-1"), OWNER))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> variantService.createVariant(PRODUCT_ID, createRequest("SKU-RACE"), OWNER))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("không phải owner → 403; product không tồn tại → 404")
        void notOwnerAndNotFound() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(productRepository.findById(404L)).thenReturn(Optional.empty());
            when(storeAccessService.requireOwnedStore(STORE_ID, STRANGER)).thenThrow(new ForbiddenException("x"));

            assertThatThrownBy(() -> variantService.createVariant(PRODUCT_ID, createRequest("SKU-X"), STRANGER))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> variantService.createVariant(404L, createRequest("SKU-X"), OWNER))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(variantRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("updateVariant — CA14")
    class Update {

        @Test
        @DisplayName("variant không thuộc product trong URL → 404")
        void wrongProduct() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(variantRepository.findByIdAndProductId(99L, PRODUCT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> variantService.updateVariant(PRODUCT_ID, 99L, statusOnly("ACTIVE"), OWNER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("cập nhật giá, tồn kho, tên và bật ACTIVE")
        void updateFields() {
            ProductVariantEntity v = variant(7L, ProductStatus.DRAFT);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(variantRepository.findByIdAndProductId(7L, PRODUCT_ID)).thenReturn(Optional.of(v));
            when(variantRepository.saveAndFlush(v)).thenReturn(v);

            VariantResponse response = variantService.updateVariant(PRODUCT_ID, 7L, new UpdateVariantRequest(
                    null, "Hộp 6 bánh", null, new BigDecimal("320000"), 5, "ACTIVE"), OWNER);

            assertThat(response.variantName()).isEqualTo("Hộp 6 bánh");
            assertThat(response.basePrice()).isEqualByComparingTo("320000");
            assertThat(response.stockQuantity()).isEqualTo(5);
            assertThat(response.status()).isEqualTo("ACTIVE");
            verify(storeAccessService).requireOwnedStore(STORE_ID, OWNER);
        }

        @Test
        @DisplayName("tắt variant ACTIVE cuối cùng khi product đang ACTIVE → 400")
        void cannotDeactivateLastActiveVariant() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.ACTIVE)));
            when(variantRepository.findByIdAndProductId(7L, PRODUCT_ID)).thenReturn(Optional.of(variant(7L, ProductStatus.ACTIVE)));
            when(variantRepository.existsByProductIdAndStatusAndIdNot(PRODUCT_ID, ProductStatus.ACTIVE, 7L)).thenReturn(false);

            assertThatThrownBy(() -> variantService.updateVariant(PRODUCT_ID, 7L, statusOnly("INACTIVE"), OWNER))
                    .isInstanceOf(BadRequestException.class);
            verify(variantRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("tắt variant ACTIVE khi còn variant ACTIVE khác → 200")
        void deactivateWhenAnotherActiveExists() {
            ProductVariantEntity v = variant(7L, ProductStatus.ACTIVE);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.ACTIVE)));
            when(variantRepository.findByIdAndProductId(7L, PRODUCT_ID)).thenReturn(Optional.of(v));
            when(variantRepository.existsByProductIdAndStatusAndIdNot(PRODUCT_ID, ProductStatus.ACTIVE, 7L)).thenReturn(true);
            when(variantRepository.saveAndFlush(v)).thenReturn(v);

            assertThat(variantService.updateVariant(PRODUCT_ID, 7L, statusOnly("INACTIVE"), OWNER).status())
                    .isEqualTo("INACTIVE");
        }

        @Test
        @DisplayName("quay về DRAFT / OUT_OF_STOCK / status lạ → 400")
        void invalidStatus() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(variantRepository.findByIdAndProductId(7L, PRODUCT_ID)).thenReturn(Optional.of(variant(7L, ProductStatus.ACTIVE)));

            assertThatThrownBy(() -> variantService.updateVariant(PRODUCT_ID, 7L, statusOnly("DRAFT"), OWNER))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> variantService.updateVariant(PRODUCT_ID, 7L, statusOnly("OUT_OF_STOCK"), OWNER))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> variantService.updateVariant(PRODUCT_ID, 7L, statusOnly("SOLD"), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("đổi SKU trùng variant khác → 409")
        void duplicateSkuOnUpdate() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(variantRepository.findByIdAndProductId(7L, PRODUCT_ID)).thenReturn(Optional.of(variant(7L, ProductStatus.DRAFT)));
            when(variantRepository.existsBySkuAndIdNot("SKU-8", 7L)).thenReturn(true);

            assertThatThrownBy(() -> variantService.updateVariant(PRODUCT_ID, 7L,
                    new UpdateVariantRequest("SKU-8", null, null, null, null, null), OWNER))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Nested
    @DisplayName("getVariants / getVariant — CA11, CA12")
    class Read {

        @Test
        @DisplayName("public xem product đã công bố chỉ nhận variant ACTIVE")
        void publicListsActiveOnly() {
            var pageable = PageRequest.of(0, 20);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.ACTIVE)));
            when(storeAccessService.isPublic(STORE_ID)).thenReturn(true);
            when(variantRepository.findByProductIdAndStatus(PRODUCT_ID, ProductStatus.ACTIVE, pageable))
                    .thenReturn(new PageImpl<>(List.of(variant(7L, ProductStatus.ACTIVE)), pageable, 1));

            assertThat(variantService.getVariants(PRODUCT_ID, null, pageable).items()).hasSize(1);
            verify(variantRepository, never()).findByProductId(any(), any());
        }

        @Test
        @DisplayName("product DRAFT: người ngoài nhận 404, owner thấy mọi variant")
        void draftProductVisibleOnlyToOwner() {
            var pageable = PageRequest.of(0, 20);
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.DRAFT)));
            when(storeAccessService.isOwner(STORE_ID, STRANGER)).thenReturn(false);
            when(storeAccessService.isOwner(STORE_ID, OWNER)).thenReturn(true);
            when(variantRepository.findByProductId(PRODUCT_ID, pageable)).thenReturn(new PageImpl<>(
                    List.of(variant(7L, ProductStatus.DRAFT), variant(8L, ProductStatus.ACTIVE)), pageable, 2));

            assertThatThrownBy(() -> variantService.getVariants(PRODUCT_ID, STRANGER, pageable))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(variantService.getVariants(PRODUCT_ID, OWNER, pageable).items()).hasSize(2);
        }

        @Test
        @DisplayName("variant DRAFT trong product đã công bố: public nhận 404")
        void draftVariantHidden() {
            when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product(ProductStatus.ACTIVE)));
            when(variantRepository.findByIdAndProductId(8L, PRODUCT_ID)).thenReturn(Optional.of(variant(8L, ProductStatus.DRAFT)));
            when(storeAccessService.isPublic(STORE_ID)).thenReturn(true);

            assertThatThrownBy(() -> variantService.getVariant(PRODUCT_ID, 8L, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
