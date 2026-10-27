package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;

/** Last message a participant has read in a conversation; messages after it from others are unread. */
@Getter
@Entity
@Table(name = "conversation_read_state", uniqueConstraints = @UniqueConstraint(name = "UQ_conversation_read_state",
        columnNames = {"conversation_id", "user_id_ref"}))
@AttributeOverride(name = "id", column = @Column(name = "conversation_read_state_id"))
public class ConversationReadStateEntity extends BaseRfqEntity {
    @Column(name = "conversation_id", nullable = false) private Long conversationId;
    @Column(name = "user_id_ref", nullable = false) private Long userIdRef;
    @Column(name = "last_read_message_id", nullable = false) private Long lastReadMessageId;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    protected ConversationReadStateEntity() {
    }

    public ConversationReadStateEntity(Long conversationId, Long userIdRef) {
        this.conversationId = conversationId;
        this.userIdRef = userIdRef;
    }

    /** Never moves backwards, so a stale request cannot mark messages unread again. */
    public void readUpTo(Long messageId, LocalDateTime now) {
        if (lastReadMessageId == null || messageId > lastReadMessageId) {
            lastReadMessageId = messageId;
            updatedAt = now;
        }
    }
}
