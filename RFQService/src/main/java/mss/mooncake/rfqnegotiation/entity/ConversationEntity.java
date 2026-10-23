package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import mss.mooncake.rfqnegotiation.enums.ConversationStatus;

@Entity
@Getter
@Setter
@Table(name = "conversation", uniqueConstraints = @UniqueConstraint(name = "UQ_conversation_rfq_supplier",
        columnNames = {"rfq_id", "supplier_id_ref"}))
@AttributeOverride(name = "id", column = @Column(name = "conversation_id"))
public class ConversationEntity extends BaseRfqEntity {
    @Column(name = "rfq_id", nullable = false) private Long rfqId;
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    @Column(name = "supplier_id_ref", nullable = false) private Long supplierIdRef;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) private ConversationStatus status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
