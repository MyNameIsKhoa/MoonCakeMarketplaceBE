package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "quotation_command")
public class QuotationCommandEntity extends BaseRfqEntity {
    @Column(name = "actor_user_id_ref", nullable = false)
    private Long actorUserId;
    @Column(name = "command_type", nullable = false, length = 20)
    private String commandType;
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    @Column(name = "request_key", nullable = false, length = 100)
    private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(name = "quotation_id", nullable = false)
    private Long quotationId;

    public QuotationCommandEntity() {
    }

    public QuotationCommandEntity(Long actorUserId, String commandType, Long targetId,
                                   String requestKey, String requestHash, Long quotationId) {
        this.actorUserId = actorUserId;
        this.commandType = commandType;
        this.targetId = targetId;
        this.requestKey = requestKey;
        this.requestHash = requestHash;
        this.quotationId = quotationId;
    }

    public String getRequestHash() { return requestHash; }
    public Long getQuotationId() { return quotationId; }
}
