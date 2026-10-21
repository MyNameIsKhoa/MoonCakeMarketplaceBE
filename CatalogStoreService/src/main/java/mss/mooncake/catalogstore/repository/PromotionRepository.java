package mss.mooncake.catalogstore.repository;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import mss.mooncake.catalogstore.enums.StoreStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Listing CA16: gọi {@code findAll(PromotionRepository.publicListing(...), pageable)}.
 * Các điều kiện Specification là static method của interface (Spring Data không suy query từ static method).
 */
public interface PromotionRepository extends JpaRepository<PromotionEntity, Long>,
    JpaSpecificationExecutor<PromotionEntity> {

  /**
   * Promotion đang hiệu lực tại thời điểm {@code at}: start_at <= at < end_at.
   * Dùng cho engine IN08.
   */
  @Query("""
      select pr from PromotionEntity pr
      where pr.storeId = :storeId and pr.status = :status
        and pr.startAt <= :at and pr.endAt > :at
      """)
  List<PromotionEntity> findEffective(@Param("storeId") Long storeId,
      @Param("status") PromotionStatus status,
      @Param("at") LocalDateTime at);

  // ─── Specification cho listing (CA16); filter null được bỏ qua ─────────────────

  /** Public: promotion ACTIVE, chưa kết thúc tại {@code now}, thuộc store ACTIVE. */
  static Specification<PromotionEntity> publicListing(Long storeId, LocalDateTime now) {
    List<Specification<PromotionEntity>> specs = new ArrayList<>();
    specs.add((root, query, cb) -> cb.equal(root.get("status"), PromotionStatus.ACTIVE));
    specs.add((root, query, cb) -> cb.greaterThan(root.get("endAt"), now));
    specs.add(storeHasStatus(StoreStatus.ACTIVE));
    if (storeId != null) specs.add((root, query, cb) -> cb.equal(root.get("storeId"), storeId));
    return Specification.allOf(specs);
  }

  /** Chủ store (mine=true): mọi trạng thái trong các store của supplier. */
  static Specification<PromotionEntity> ownerListing(Collection<Long> ownedStoreIds, Long storeId) {
    List<Specification<PromotionEntity>> specs = new ArrayList<>();
    // Danh sách rỗng trả về điều kiện luôn sai, tránh sinh SQL "IN ()".
    specs.add((root, query, cb) -> ownedStoreIds.isEmpty() ? cb.disjunction() : root.get("storeId").in(ownedStoreIds));
    if (storeId != null) specs.add((root, query, cb) -> cb.equal(root.get("storeId"), storeId));
    return Specification.allOf(specs);
  }

  /** Promotion không có quan hệ JPA tới Store (chỉ giữ storeId) nên dùng subquery EXISTS. */
  private static Specification<PromotionEntity> storeHasStatus(StoreStatus status) {
    return (root, query, cb) -> {
      Subquery<Long> sub = query.subquery(Long.class);
      Root<StoreEntity> store = sub.from(StoreEntity.class);
      sub.select(store.get("id"))
          .where(cb.equal(store.get("id"), root.get("storeId")),
              cb.equal(store.get("status"), status));
      return cb.exists(sub);
    };
  }
}
