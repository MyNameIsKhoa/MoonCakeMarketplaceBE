package mss.mooncake.catalogstore.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@DisplayName("ProductRepository search — search CA06 trên H2")
class ProductRepositorySearchTest {

    @Autowired ProductRepository productRepository;
    @Autowired StoreRepository storeRepository;

    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("id"));

    private Long activeStoreId;
    private Long inactiveStoreId;

    @BeforeEach
    void seed() {
        activeStoreId = store("STR-AAAAAAAAAAAA", "store-a", StoreStatus.ACTIVE);
        inactiveStoreId = store("STR-BBBBBBBBBBBB", "store-b", StoreStatus.INACTIVE);

        product(activeStoreId, 1L, "PRD-1", "Bánh nướng thập cẩm", ProductStatus.ACTIVE);
        product(activeStoreId, 2L, "PRD-2", "Bánh dẻo hạt sen", ProductStatus.ACTIVE);
        product(activeStoreId, 1L, "PRD-3", "Bánh nướng đậu xanh", ProductStatus.DRAFT);
        product(inactiveStoreId, 1L, "PRD-4", "Bánh nướng trà xanh", ProductStatus.ACTIVE);
        product(activeStoreId, 1L, "PRD-5", "Hộp quà 100% handmade", ProductStatus.ACTIVE);
    }

    private Long store(String code, String slug, StoreStatus status) {
        StoreEntity s = new StoreEntity();
        s.setSupplierIdRef(1L);
        s.setStoreCode(code);
        s.setName(slug);
        s.setSlug(slug);
        s.setStatus(status);
        return storeRepository.save(s).getId();
    }

    private void product(Long storeId, Long categoryId, String code, String name, ProductStatus status) {
        ProductEntity p = new ProductEntity();
        p.setStoreId(storeId);
        p.setCategoryId(categoryId);
        p.setProductCode(code);
        p.setName(name);
        p.setStatus(status);
        productRepository.save(p);
    }

    private List<String> codes(org.springframework.data.jpa.domain.Specification<ProductEntity> spec) {
        return productRepository.findAll(spec, PAGE).map(ProductEntity::getProductCode).getContent();
    }

    @Test
    @DisplayName("public: bỏ product DRAFT và product thuộc store không ACTIVE")
    void publicSearchHidesDraftAndUnpublishedStore() {
        assertEquals(List.of("PRD-1", "PRD-2", "PRD-5"), codes(ProductRepository.publicSearch(null, null, null)));
    }

    @Test
    @DisplayName("public: q không phân biệt hoa thường, kết hợp categoryId")
    void publicSearchByNameAndCategory() {
        assertEquals(List.of("PRD-1"), codes(ProductRepository.publicSearch("  BÁNH NƯỚNG ", null, 1L)));
    }

    @Test
    @DisplayName("public: ký tự % trong q được hiểu là ký tự thường")
    void publicSearchEscapesWildcards() {
        assertEquals(List.of("PRD-5"), codes(ProductRepository.publicSearch("100%", null, null)));
        assertTrue(codes(ProductRepository.publicSearch("%", null, 2L)).isEmpty());
    }

    @Test
    @DisplayName("owner: thấy cả DRAFT trong store của mình, không thấy store khác")
    void ownerSearchIncludesDraftOfOwnedStores() {
        assertEquals(List.of("PRD-1", "PRD-2", "PRD-3", "PRD-5"),
                codes(ProductRepository.ownerSearch(List.of(activeStoreId), null, null, null)));
        assertEquals(List.of("PRD-4"),
                codes(ProductRepository.ownerSearch(List.of(inactiveStoreId), "nướng", null, null)));
    }

    @Test
    @DisplayName("owner: user không có store nào trả rỗng thay vì lỗi SQL IN ()")
    void ownerSearchWithoutStoresReturnsEmpty() {
        assertTrue(codes(ProductRepository.ownerSearch(List.of(), null, null, null)).isEmpty());
    }
}
