package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_address")
@AttributeOverride(name = "id", column = @Column(name = "delivery_address_id"))
public class DeliveryAddressEntity extends BaseCommerceEntity {
    @Column(name = "order_id", nullable = false, unique = true) private Long orderId;
    @Column(name = "source_address_id_ref") private Long sourceAddressIdRef;
    @Column(name = "recipient_name", nullable = false, length = 255) private String recipientName;
    @Column(name = "recipient_phone", nullable = false, length = 30) private String recipientPhone;
    @Column(name = "address_line", nullable = false, length = 500) private String addressLine;
    @Column(name = "ward", length = 100) private String ward;
    @Column(name = "district", length = 100) private String district;
    @Column(name = "city", nullable = false, length = 100) private String city;
}
