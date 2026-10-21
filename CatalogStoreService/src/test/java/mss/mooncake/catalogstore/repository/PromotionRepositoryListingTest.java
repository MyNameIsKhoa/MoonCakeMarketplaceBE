package mss.mooncake.catalogstore.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@DisplayName("PromotionRepository listing — listing CA16 trên H2")
class PromotionRepositoryListingTest {

    @Autowired PromotionRepository promotionRepository;
    @Autowired StoreRepository storeRepository;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final PageRequest PAGE = PageRequest.of(0, 20, Sort.by("id"));

    private Long activeStore;
    private Long inactiveStore;

    @BeforeEach
    void seed() {
        activeStore = store("STR-PROMOACTIVE1", "promo-active", StoreStatus.ACTIVE);
        inactiveStore = store("STR-PROMOINACT01", "promo-inactive", StoreStatus.INACTIVE);

        promotion(activeStore, "dang-chay", PromotionStatus.ACTIVE, NOW.plusDays(5));
        promotion(activeStore, "da-ket-thuc", PromotionStatus.ACTIVE, NOW.minusDays(1));
        promotion(activeStore, "nhap", PromotionStatus.DRAFT, NOW.plusDays(5));
        promotion(inactiveStore, "store-tat", PromotionStatus.ACTIVE, NOW.plusDays(5));
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

    private void promotion(Long storeId, String name, PromotionStatus status, LocalDateTime endAt) {
        PromotionEntity p = new PromotionEntity();
        p.setStoreId(storeId);
        p.setName(name);
        p.setStartAt(NOW.minusDays(10));
        p.setEndAt(endAt);
        p.setStatus(status);
        promotionRepository.save(p);
    }

    private List<String> names(Specification<PromotionEntity> spec) {
        return promotionRepository.findAll(spec, PAGE).map(PromotionEntity::getName).getContent();
    }

    @Test
    @DisplayName("public: chỉ ACTIVE, chưa kết thúc, thuộc store ACTIVE")
    void publicListing() {
        assertThat(names(PromotionRepository.publicListing(null, NOW))).containsExactly("dang-chay");
        assertThat(names(PromotionRepository.publicListing(inactiveStore, NOW))).isEmpty();
    }

    @Test
    @DisplayName("owner: mọi trạng thái trong store của mình; không có store → rỗng")
    void ownerListing() {
        assertThat(names(PromotionRepository.ownerListing(List.of(activeStore), null)))
                .containsExactly("dang-chay", "da-ket-thuc", "nhap");
        assertThat(names(PromotionRepository.ownerListing(List.of(), null))).isEmpty();
    }
}
