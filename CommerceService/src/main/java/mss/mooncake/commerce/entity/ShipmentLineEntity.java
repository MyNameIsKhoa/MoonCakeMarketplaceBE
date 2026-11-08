package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Units of one order item carried by one shipment (batch) of the same seller order. */
@Entity
@Table(name = "shipment_line")
@AttributeOverride(name = "id", column = @Column(name = "shipment_line_id"))
public class ShipmentLineEntity extends BaseCommerceEntity {

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    public ShipmentLineEntity() {}

    public ShipmentLineEntity(Long shipmentId, Long orderItemId, Integer quantity) {
        this.shipmentId = shipmentId;
        this.orderItemId = orderItemId;
        this.quantity = quantity;
    }

    public Long getShipmentId() { return shipmentId; }
    public Long getOrderItemId() { return orderItemId; }
    public Integer getQuantity() { return quantity; }
}
