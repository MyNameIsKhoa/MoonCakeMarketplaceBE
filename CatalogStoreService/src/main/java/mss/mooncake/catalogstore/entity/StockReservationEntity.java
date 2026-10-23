package mss.mooncake.catalogstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_reservation")
public class StockReservationEntity {
    @Id
    @Column(name = "reservation_key", length = 100)
    private String reservationKey;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected StockReservationEntity() { }

    public StockReservationEntity(String reservationKey, LocalDateTime expiresAt) {
        this.reservationKey = reservationKey;
        this.expiresAt = expiresAt;
        this.status = "HELD";
    }

    public String getReservationKey() { return reservationKey; }
    public String getStatus() { return status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setStatus(String status) { this.status = status; }
}
