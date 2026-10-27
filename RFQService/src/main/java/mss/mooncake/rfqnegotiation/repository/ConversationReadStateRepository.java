package mss.mooncake.rfqnegotiation.repository;

import java.util.Optional;
import mss.mooncake.rfqnegotiation.entity.ConversationReadStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationReadStateRepository extends JpaRepository<ConversationReadStateEntity, Long> {
    Optional<ConversationReadStateEntity> findByConversationIdAndUserIdRef(Long conversationId, Long userIdRef);
}
