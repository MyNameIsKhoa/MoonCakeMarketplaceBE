package mss.mooncake.catalogstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stock_reservation_line")
public class StockReservationLineEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_line_id")
    private Long id;
    @Column(name = "reservation_key", nullable = false, length = 100)
    private String reservationKey;
    @Column(name = "variant_id", nullable = false)
    private Long variantId;
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    protected StockReservationLineEntity() { }

    public StockReservationLineEntity(String reservationKey, Long variantId, Integer quantity) {
        this.reservationKey = reservationKey;
        this.variantId = variantId;
        this.quantity = quantity;
    }

    public Long getVariantId() { return variantId; }
    public Integer getQuantity() { return quantity; }
}
