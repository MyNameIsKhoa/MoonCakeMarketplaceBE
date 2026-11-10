package mss.mooncake.catalogstore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import mss.mooncake.catalogstore.dto.request.CreateProductRequest;
import mss.mooncake.catalogstore.dto.request.UpdateProductRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.ProductResponse;
import mss.mooncake.catalogstore.dto.response.ProductSummaryResponse;
import mss.mooncake.catalogstore.entity.CategoryEntity;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.CategoryStatus;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.CategoryRepository;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StockReservationLineRepository;
import mss.mooncake.catalogstore.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductServiceImpl Unit Tests")
class ProductServiceImplTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private StoreAccessService storeAccessService;

    @Mock private StockReservationLineRepository reservationLines;
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        productService = new ProductServiceImpl(productRepository, variantRepository, categoryRepository,
                storeAccessService, reservationLines, Clock.systemUTC());
    }

    @Test
    void listUsesRemainingStockAndPricesOnlyPurchasableVariants() {
        var pageable = PageRequest.of(0, 10);
        when(productRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(product(5L, ProductStatus.ACTIVE), product(6L, ProductStatus.ACTIVE))));
        when(variantRepository.findByProductIdInOrderByIdAsc(List.of(5L, 6L))).thenReturn(List.of(
                variant(7L, 5L, "100", ProductStatus.ACTIVE, null),
                variant(8L, 5L, "200", ProductStatus.ACTIVE, null),
                variant(9L, 6L, "150", ProductStatus.ACTIVE, null)));
        var heldSeven = org.mockito.Mockito.mock(StockReservationLineRepository.HeldQuantity.class);
        var heldNine = org.mockito.Mockito.mock(StockReservationLineRepository.HeldQuantity.class);
        when(heldSeven.getVariantId()).thenReturn(7L);
        when(heldSeven.getQuantity()).thenReturn(10L);
        when(heldNine.getVariantId()).thenReturn(9L);
        when(heldNine.getQuantity()).thenReturn(10L);
        when(reservationLines.heldQuantities(anyList(), any())).thenReturn(List.of(heldSeven, heldNine));

        var products = productService.searchProducts(null, null, null, false, null, pageable).items();
        assertThat(products.getFirst().purchasable()).isTrue();
        assertThat(products.getFirst().minPrice()).isEqualByComparingTo("200");
        assertThat(products.getLast().purchasable()).isFalse();
        assertThat(products.getLast().minPrice()).isNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────

    private static final Long STORE_ID = 1L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long OWNER = 10L;
    private static final Long STRANGER = 20L;

    private static ProductEntity product(Long id, ProductStatus status) {
        ProductEntity p = new ProductEntity();
        ReflectionTestUtils.setField(p, "id", id);
        p.setStoreId(STORE_ID);
        p.setCategoryId(CATEGORY_ID);
        p.setProductCode("PRD-0000000" + id);
        p.setName("Bánh " + id);
        p.setStatus(status);
        return p;
    }

    private static ProductVariantEntity variant(Long id, Long productId, String price, ProductStatus status, String image) {
        ProductVariantEntity v = new ProductVariantEntity();
        ReflectionTestUtils.setField(v, "id", id);
        v.setProductId(productId);
        v.setSku("SKU-" + id);
        v.setVariantName("Hộp " + id);
        v.setImageUrl(image);
        v.setBasePrice(new BigDecimal(price));
        v.setStockQuantity(10);
        v.setStatus(status);
        return v;
    }

    private void categoryActive() {
        CategoryEntity c = new CategoryEntity();
        c.setStatus(CategoryStatus.ACTIVE);
        when(categoryRepository.findByIdAndStatus(CATEGORY_ID, CategoryStatus.ACTIVE)).thenReturn(Optional.of(c));
    }

    @Nested
    @DisplayName("createProduct — CA08")
    class Create {

        @Test
        @DisplayName("tạo DRAFT với productCode do server sinh dạng PRD-XXXXXXXX")
        void createDraft() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            categoryActive();
            when(productRepository.existsByStoreIdAndProductCode(eq(STORE_ID), any())).thenReturn(false);
            when(productRepository.save(any(ProductEntity.class))).thenAnswer(inv -> {
                ProductEntity saved = inv.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 50L);
                return saved;
            });

            ProductResponse response = productService.createProduct(
                    new CreateProductRequest(STORE_ID, CATEGORY_ID, "  Bánh nướng  ", "Nhân thập cẩm"), OWNER);

            assertThat(response.productId()).isEqualTo(50L);
            assertThat(response.status()).isEqualTo("DRAFT");
            assertThat(response.name()).isEqualTo("Bánh nướng");
            assertThat(response.productCode()).matches("PRD-[0-9A-F]{8}");
            assertThat(response.variants()).isEmpty();
        }

        @Test
        @DisplayName("không phải owner store → 403, không ghi DB")
        void notOwner() {
            when(storeAccessService.requireOwnedStore(STORE_ID, STRANGER)).thenThrow(new ForbiddenException("x"));

            assertThatThrownBy(() -> productService.createProduct(
                    new CreateProductRequest(STORE_ID, CATEGORY_ID, "Bánh", null), STRANGER))
                    .isInstanceOf(ForbiddenException.class);
            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("category INACTIVE hoặc không tồn tại → 400")
        void inactiveCategory() {
            when(storeAccessService.requireOwnedStore(STORE_ID, OWNER)).thenReturn(new StoreEntity());
            when(categoryRepository.findByIdAndStatus(CATEGORY_ID, CategoryStatus.ACTIVE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.createProduct(
                    new CreateProductRequest(STORE_ID, CATEGORY_ID, "Bánh", null), OWNER))
                    .isInstanceOf(BadRequestException.class);
            verify(productRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateProduct — CA09")
    class Update {

        @Test
        @DisplayName("lên ACTIVE khi chưa có variant ACTIVE → 400")
        void activateWithoutActiveVariant() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.DRAFT)));
            when(variantRepository.existsByProductIdAndStatus(5L, ProductStatus.ACTIVE)).thenReturn(false);

            assertThatThrownBy(() -> productService.updateProduct(5L,
                    new UpdateProductRequest(null, null, null, "ACTIVE"), OWNER))
                    .isInstanceOf(BadRequestException.class);
            verify(productRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("lên ACTIVE khi đã có variant ACTIVE → 200")
        void activateWithActiveVariant() {
            ProductEntity p = product(5L, ProductStatus.DRAFT);
            when(productRepository.findById(5L)).thenReturn(Optional.of(p));
            when(variantRepository.existsByProductIdAndStatus(5L, ProductStatus.ACTIVE)).thenReturn(true);
            when(productRepository.saveAndFlush(p)).thenReturn(p);
            when(variantRepository.findByProductIdInOrderByIdAsc(List.of(5L)))
                    .thenReturn(List.of(variant(7L, 5L, "100000", ProductStatus.ACTIVE, null)));

            ProductResponse response = productService.updateProduct(5L,
                    new UpdateProductRequest(null, "Tên mới", null, "active"), OWNER);

            assertThat(response.status()).isEqualTo("ACTIVE");
            assertThat(response.name()).isEqualTo("Tên mới");
            verify(storeAccessService).requireOwnedStore(STORE_ID, OWNER);
        }

        @Test
        @DisplayName("quay lại DRAFT hoặc đặt tay OUT_OF_STOCK → 400")
        void forbiddenTransitions() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.ACTIVE)));

            assertThatThrownBy(() -> productService.updateProduct(5L,
                    new UpdateProductRequest(null, null, null, "DRAFT"), OWNER))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> productService.updateProduct(5L,
                    new UpdateProductRequest(null, null, null, "OUT_OF_STOCK"), OWNER))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("supplier khác sửa product → 403")
        void notOwner() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.DRAFT)));
            when(storeAccessService.requireOwnedStore(STORE_ID, STRANGER)).thenThrow(new ForbiddenException("x"));

            assertThatThrownBy(() -> productService.updateProduct(5L,
                    new UpdateProductRequest(null, "Hack", null, null), STRANGER))
                    .isInstanceOf(ForbiddenException.class);
            verify(productRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("tên toàn khoảng trắng → 400; product không tồn tại → 404")
        void blankNameAndNotFound() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.DRAFT)));
            when(productRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.updateProduct(5L,
                    new UpdateProductRequest(null, "  ", null, null), OWNER))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> productService.updateProduct(404L,
                    new UpdateProductRequest(null, "x", null, null), OWNER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getProduct — CA07")
    class Detail {

        @Test
        @DisplayName("product DRAFT: người ngoài nhận 404")
        void draftHiddenFromStranger() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.DRAFT)));
            when(storeAccessService.isOwner(STORE_ID, STRANGER)).thenReturn(false);

            assertThatThrownBy(() -> productService.getProduct(5L, STRANGER))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> productService.getProduct(5L, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("owner xem product DRAFT thấy mọi variant")
        void ownerSeesDraftAndAllVariants() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.DRAFT)));
            when(storeAccessService.isOwner(STORE_ID, OWNER)).thenReturn(true);
            when(variantRepository.findByProductIdInOrderByIdAsc(List.of(5L))).thenReturn(List.of(
                    variant(7L, 5L, "100000", ProductStatus.ACTIVE, null),
                    variant(8L, 5L, "90000", ProductStatus.DRAFT, null)));

            assertThat(productService.getProduct(5L, OWNER).variants()).hasSize(2);
        }

        @Test
        @DisplayName("public xem product ACTIVE chỉ thấy variant ACTIVE")
        void publicSeesOnlyActiveVariants() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.ACTIVE)));
            when(storeAccessService.isPublic(STORE_ID)).thenReturn(true);
            when(variantRepository.findByProductIdInOrderByIdAsc(List.of(5L))).thenReturn(List.of(
                    variant(7L, 5L, "100000", ProductStatus.ACTIVE, null),
                    variant(8L, 5L, "90000", ProductStatus.DRAFT, null)));

            assertThat(productService.getProduct(5L, null).variants())
                    .extracting(v -> v.variantId()).containsExactly(7L);
        }

        @Test
        @DisplayName("product ACTIVE nhưng store không ACTIVE → 404 cho public")
        void unpublishedStoreHidden() {
            when(productRepository.findById(5L)).thenReturn(Optional.of(product(5L, ProductStatus.ACTIVE)));
            when(storeAccessService.isPublic(STORE_ID)).thenReturn(false);

            assertThatThrownBy(() -> productService.getProduct(5L, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("searchProducts — CA06")
    class Search {

        @Test
        @DisplayName("minPrice và imageUrl chỉ lấy từ variant ACTIVE, load variant một lần cho cả trang")
        @SuppressWarnings("unchecked")
        void summaryFromActiveVariants() {
            var pageable = PageRequest.of(0, 20);
            when(productRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(
                    new PageImpl<>(List.of(product(5L, ProductStatus.ACTIVE), product(6L, ProductStatus.ACTIVE)), pageable, 2));
            when(variantRepository.findByProductIdInOrderByIdAsc(List.of(5L, 6L))).thenReturn(List.of(
                    variant(7L, 5L, "120000", ProductStatus.ACTIVE, null),
                    variant(8L, 5L, "50000", ProductStatus.DRAFT, "draft.png"),
                    variant(9L, 5L, "100000", ProductStatus.ACTIVE, "active.png")));

            PageResponse<ProductSummaryResponse> page = productService.searchProducts(null, null, null, false, null, pageable);

            ProductSummaryResponse first = page.items().get(0);
            assertThat(first.minPrice()).isEqualByComparingTo("100000");
            assertThat(first.imageUrl()).isEqualTo("active.png");
            ProductSummaryResponse second = page.items().get(1);
            assertThat(second.minPrice()).isNull();
            assertThat(second.imageUrl()).isNull();
            verify(variantRepository).findByProductIdInOrderByIdAsc(anyList());
        }

        @Test
        @DisplayName("mine=true dùng danh sách store của supplier")
        @SuppressWarnings("unchecked")
        void mineUsesOwnedStores() {
            var pageable = PageRequest.of(0, 20);
            when(storeAccessService.findOwnedStoreIds(OWNER)).thenReturn(List.of(STORE_ID));
            when(productRepository.findAll(any(Specification.class), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(), pageable, 0));

            PageResponse<ProductSummaryResponse> page = productService.searchProducts(null, null, null, true, OWNER, pageable);

            assertThat(page.items()).isEmpty();
            verify(storeAccessService).findOwnedStoreIds(OWNER);
            verify(variantRepository, never()).findByProductIdInOrderByIdAsc(anyList());
        }
    }
}
