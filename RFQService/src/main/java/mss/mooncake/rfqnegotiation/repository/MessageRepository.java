package mss.mooncake.rfqnegotiation.repository;
import mss.mooncake.rfqnegotiation.entity.MessageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    Optional<MessageEntity> findByConversationIdAndSenderUserIdRefAndClientMessageId(Long conversationId,
            Long senderUserId, String clientMessageId);
    boolean existsByIdAndConversationId(Long messageId, Long conversationId);

    @Query("select m from MessageEntity m where m.conversationId = :conversationId "
            + "and (:beforeId is null or m.id < :beforeId) order by m.id desc")
    List<MessageEntity> findBefore(@Param("conversationId") Long conversationId,
            @Param("beforeId") Long beforeId, Pageable pageable);

    @Query("select m from MessageEntity m where m.conversationId = :conversationId "
            + "and m.id > :afterId order by m.id asc")
    List<MessageEntity> findAfter(@Param("conversationId") Long conversationId,
            @Param("afterId") Long afterId, Pageable pageable);
}
