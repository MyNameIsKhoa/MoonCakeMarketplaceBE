package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "conversation_message", uniqueConstraints = @UniqueConstraint(name = "UQ_message_client_id",
        columnNames = {"conversation_id", "sender_user_id_ref", "client_message_id"}))
@AttributeOverride(name = "id", column = @Column(name = "message_id"))
public class MessageEntity extends BaseRfqEntity {
    @Column(name = "conversation_id", nullable = false) private Long conversationId;
    @Column(name = "sender_user_id_ref", nullable = false) private Long senderUserIdRef;
    @Column(name = "message_type", nullable = false, length = 30) private String messageType;
    @Column(name = "client_message_id", nullable = false, length = 100) private String clientMessageId;
    @Column(name = "content", nullable = false, columnDefinition = "NVARCHAR(MAX)") private String content;
    @Column(name = "sent_at", nullable = false) private LocalDateTime sentAt;
}
