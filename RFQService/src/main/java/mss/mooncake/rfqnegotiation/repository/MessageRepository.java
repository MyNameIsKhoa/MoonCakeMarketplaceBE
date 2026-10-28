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

    @Query("select max(m.id) from MessageEntity m where m.conversationId = :conversationId")
    Long findLatestId(@Param("conversationId") Long conversationId);

    /** Same participant rule as ConversationRepository.findForParticipant; null IDs match nothing. */
    @Query("select count(m) from MessageEntity m, ConversationEntity c where m.conversationId = c.id "
            + "and (c.customerIdRef = :customerId or c.supplierIdRef = :supplierId) "
            + "and m.senderUserIdRef <> :userId "
            + "and not exists (select r.id from ConversationReadStateEntity r where r.conversationId = c.id "
            + "and r.userIdRef = :userId and r.lastReadMessageId >= m.id)")
    long countUnread(@Param("userId") Long userId, @Param("customerId") Long customerId,
            @Param("supplierId") Long supplierId);
}
