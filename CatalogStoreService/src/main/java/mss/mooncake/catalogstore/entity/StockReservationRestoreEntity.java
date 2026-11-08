package mss.mooncake.catalogstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** Confirmed units of one reservation line returned to stock under a caller's restore key. */
@Entity
@Table(name = "stock_reservation_restore",
        uniqueConstraints = @UniqueConstraint(name = "UQ_stock_reservation_restore_line",
                columnNames = {"restore_key", "variant_id"}))
public class StockReservationRestoreEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "restore_line_id")
    private Long id;
    @Column(name = "restore_key", nullable = false, length = 150)
    private String restoreKey;
    @Column(name = "reservation_key", nullable = false, length = 100)
    private String reservationKey;
    @Column(name = "variant_id", nullable = false)
    private Long variantId;
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
    @Column(name = "restored_at", nullable = false)
    private LocalDateTime restoredAt;

    protected StockReservationRestoreEntity() { }

    public StockReservationRestoreEntity(String restoreKey, String reservationKey, Long variantId, Integer quantity,
            LocalDateTime restoredAt) {
        this.restoreKey = restoreKey;
        this.reservationKey = reservationKey;
        this.variantId = variantId;
        this.quantity = quantity;
        this.restoredAt = restoredAt;
    }

    public String getRestoreKey() { return restoreKey; }
    public String getReservationKey() { return reservationKey; }
    public Long getVariantId() { return variantId; }
    public Integer getQuantity() { return quantity; }
}
