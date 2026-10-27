package mss.mooncake.rfqnegotiation.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/** A store invited to a SELECTED_STORES RFQ; supplier and name are snapshotted from Catalog at creation. */
@Getter
@Entity
@Table(name = "rfq_target_store")
@AttributeOverride(name = "id", column = @Column(name = "rfq_target_store_id"))
public class RfqTargetStoreEntity extends BaseRfqEntity {
    @Column(name = "rfq_id", nullable = false) private Long rfqId;
    @Column(name = "store_id_ref", nullable = false) private Long storeIdRef;
    @Column(name = "supplier_id_ref", nullable = false) private Long supplierIdRef;
    @Column(name = "store_name_snapshot", nullable = false, length = 255) private String storeNameSnapshot;

    protected RfqTargetStoreEntity() {
    }

    public RfqTargetStoreEntity(Long rfqId, Long storeIdRef, Long supplierIdRef, String storeNameSnapshot) {
        this.rfqId = rfqId;
        this.storeIdRef = storeIdRef;
        this.supplierIdRef = supplierIdRef;
        this.storeNameSnapshot = storeNameSnapshot;
    }
}
