package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversation")
@AttributeOverride(name = "id", column = @Column(name = "conversation_id"))
public class ConversationEntity extends BaseRfqEntity {
    @Column(name = "rfq_id", nullable = false) private Long rfqId;
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    @Column(name = "supplier_id_ref", nullable = false) private Long supplierIdRef;
    @Column(name = "status", nullable = false, length = 30) private String status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
