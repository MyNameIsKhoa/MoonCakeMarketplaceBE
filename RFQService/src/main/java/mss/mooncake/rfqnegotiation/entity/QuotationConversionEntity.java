package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Durable intent survives a crash or an unknown outcome from Commerce. */
@Entity @Getter @Setter
@Table(name = "quotation_conversion", uniqueConstraints = {
        @UniqueConstraint(columnNames = "quotation_id"),
        @UniqueConstraint(columnNames = {"customer_id_ref", "idempotency_key"})})
@AttributeOverride(name = "id", column = @Column(name = "conversion_id"))
public class QuotationConversionEntity extends BaseRfqEntity {
    @Column(name = "quotation_id", nullable = false) private Long quotationId;
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    @Column(name = "idempotency_key", nullable = false, length = 100) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "NVARCHAR(MAX)") private String snapshotJson;
    @Column(name = "status", nullable = false, length = 30) private String status;
    @Column(name = "order_id_ref") private Long orderIdRef;
    @Column(name = "seller_order_id_ref") private Long sellerOrderIdRef;
    @Column(name = "accepted_at", nullable = false) private LocalDateTime acceptedAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
}
