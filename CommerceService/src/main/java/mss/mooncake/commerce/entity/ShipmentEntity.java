package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import mss.mooncake.commerce.enums.ShipmentStatus;

@Entity
@Table(name = "shipment")
@AttributeOverride(name = "id", column = @Column(name = "shipment_id"))
public class ShipmentEntity extends BaseCommerceEntity {

    @Column(name = "seller_order_id", nullable = false)
    private Long sellerOrderId;

    @Column(name = "shipping_method", nullable = false, length = 100)
    private String shippingMethod;

    @Column(name = "provider", length = 100)
    private String provider;

    @Column(name = "tracking_code", length = 255)
    private String trackingCode;

    @Column(name = "expected_ship_at")
    private LocalDateTime expectedShipAt;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "expected_delivery_at")
    private LocalDateTime expectedDeliveryAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "shipping_fee", nullable = false, precision = 18, scale = 2)
    private BigDecimal shippingFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ShipmentStatus status;

    public ShipmentEntity() {}

    public ShipmentEntity(Long sellerOrderId, String shippingMethod, String provider, String trackingCode,
                          LocalDateTime expectedShipAt, LocalDateTime expectedDeliveryAt, BigDecimal shippingFee,
                          ShipmentStatus status) {
        this.sellerOrderId = sellerOrderId;
        this.shippingMethod = shippingMethod;
        this.provider = provider;
        this.trackingCode = trackingCode;
        this.expectedShipAt = expectedShipAt;
        this.expectedDeliveryAt = expectedDeliveryAt;
        this.shippingFee = shippingFee;
        this.status = status;
    }

    public Long getSellerOrderId() { return sellerOrderId; }
    public void setSellerOrderId(Long sellerOrderId) { this.sellerOrderId = sellerOrderId; }

    public String getShippingMethod() { return shippingMethod; }
    public void setShippingMethod(String shippingMethod) { this.shippingMethod = shippingMethod; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }

    public LocalDateTime getExpectedShipAt() { return expectedShipAt; }
    public void setExpectedShipAt(LocalDateTime expectedShipAt) { this.expectedShipAt = expectedShipAt; }

    public LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(LocalDateTime shippedAt) { this.shippedAt = shippedAt; }

    public LocalDateTime getExpectedDeliveryAt() { return expectedDeliveryAt; }
    public void setExpectedDeliveryAt(LocalDateTime expectedDeliveryAt) { this.expectedDeliveryAt = expectedDeliveryAt; }

    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(LocalDateTime deliveredAt) { this.deliveredAt = deliveredAt; }

    public BigDecimal getShippingFee() { return shippingFee; }
    public void setShippingFee(BigDecimal shippingFee) { this.shippingFee = shippingFee; }

    public ShipmentStatus getStatus() { return status; }
    public void setStatus(ShipmentStatus status) { this.status = status; }
}
