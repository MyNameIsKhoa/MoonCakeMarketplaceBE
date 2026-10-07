package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_message")
@AttributeOverride(name = "id", column = @Column(name = "message_id"))
public class MessageEntity extends BaseRfqEntity {
    @Column(name = "conversation_id", nullable = false) private Long conversationId;
    @Column(name = "sender_user_id_ref", nullable = false) private Long senderUserIdRef;
    @Column(name = "message_type", nullable = false, length = 30) private String messageType;
    @Column(name = "content", nullable = false, columnDefinition = "NVARCHAR(MAX)") private String content;
    @Column(name = "sent_at", nullable = false) private LocalDateTime sentAt;
}
