package mss.mooncake.catalogstore.repository;

import mss.mooncake.catalogstore.entity.PromotionRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromotionRuleRepository extends JpaRepository<PromotionRuleEntity, Long> { }
