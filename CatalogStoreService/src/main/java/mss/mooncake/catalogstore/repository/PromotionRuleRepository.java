package mss.mooncake.catalogstore.repository;

import java.util.Collection;
import java.util.List;
import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRuleRepository extends JpaRepository<PromotionRuleEntity, Long> {

    List<PromotionRuleEntity> findByPromotionIdOrderByIdAsc(Long promotionId);

    List<PromotionRuleEntity> findByPromotionIdIn(Collection<Long> promotionIds);

    /**
     * CA20 thay toàn bộ rule: xóa rule cũ rồi insert mới trong cùng transaction.
     */
    @Modifying
    @Query("delete from PromotionRuleEntity r where r.promotionId = :promotionId")
    void deleteByPromotionId(@Param("promotionId") Long promotionId);
}
