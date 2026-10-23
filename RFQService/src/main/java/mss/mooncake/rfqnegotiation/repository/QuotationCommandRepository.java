package mss.mooncake.rfqnegotiation.repository;

import java.util.Optional;
import mss.mooncake.rfqnegotiation.entity.QuotationCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotationCommandRepository extends JpaRepository<QuotationCommandEntity, Long> {
    Optional<QuotationCommandEntity> findByActorUserIdAndCommandTypeAndTargetIdAndRequestKey(
            Long actorUserId, String commandType, Long targetId, String requestKey);
}
