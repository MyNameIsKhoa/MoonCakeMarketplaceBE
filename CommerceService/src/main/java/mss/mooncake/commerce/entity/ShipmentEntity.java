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
    @Column(name = "seller_order_id", nullable = false) private Long sellerOrderId;
    @Column(name = "shipping_method", nullable = false, length = 100) private String shippingMethod;
    @Column(name = "provider", length = 100) private String provider;
    @Column(name = "tracking_code", length = 255) private String trackingCode;
    @Column(name = "expected_ship_at") private LocalDateTime expectedShipAt;
    @Column(name = "shipped_at") private LocalDateTime shippedAt;
    @Column(name = "expected_delivery_at") private LocalDateTime expectedDeliveryAt;
    @Column(name = "delivered_at") private LocalDateTime deliveredAt;
    @Column(name = "shipping_fee", nullable = false, precision = 18, scale = 2) private BigDecimal shippingFee;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 30) private ShipmentStatus status;
}
