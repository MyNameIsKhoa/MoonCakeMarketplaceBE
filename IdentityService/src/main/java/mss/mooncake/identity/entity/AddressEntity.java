package mss.mooncake.identity.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "address")
@AttributeOverride(name = "id", column = @Column(name = "address_id"))
public class AddressEntity extends BaseIdentityEntity {
    @Column(name = "customer_id", nullable = false)
    private Long customerId;
    @Column(name = "recipient_name", nullable = false, length = 255)
    private String recipientName;
    @Column(name = "recipient_phone", nullable = false, length = 30)
    private String recipientPhone;
    @Column(name = "address_line", nullable = false, length = 500)
    private String addressLine;
    @Column(name = "ward", length = 100)
    private String ward;
    @Column(name = "district", length = 100)
    private String district;
    @Column(name = "city", nullable = false, length = 100)
    private String city;
    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;
}
