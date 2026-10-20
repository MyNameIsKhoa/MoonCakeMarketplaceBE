package mss.mooncake.catalogstore.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import mss.mooncake.catalogstore.entity.PromotionEntity;
import mss.mooncake.catalogstore.enums.PromotionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<PromotionEntity, Long> {

  Page<PromotionEntity> findByStoreIdIn(Collection<Long> storeIds, Pageable pageable);

  Page<PromotionEntity> findByStoreIdAndStatus(Long storeId, PromotionStatus status, Pageable pageable);

  Page<PromotionEntity> findByStatus(PromotionStatus status, Pageable pageable);

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
}
