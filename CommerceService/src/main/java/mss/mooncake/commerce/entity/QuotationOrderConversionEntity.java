package mss.mooncake.commerce.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Getter @Setter
@Table(name="quotation_order_conversion", uniqueConstraints={@UniqueConstraint(columnNames="quotation_id_ref"),@UniqueConstraint(columnNames="idempotency_key")})
@AttributeOverride(name="id",column=@Column(name="conversion_id"))
public class QuotationOrderConversionEntity extends BaseCommerceEntity {
    @Column(name="quotation_id_ref",nullable=false) private Long quotationIdRef;
    @Column(name="quotation_version",nullable=false) private Integer quotationVersion;
    @Column(name="idempotency_key",nullable=false,length=100) private String idempotencyKey;
    @Column(name="request_hash",nullable=false,length=64) private String requestHash;
    @Column(name="order_id") private Long orderId;
    @Column(name="seller_order_id") private Long sellerOrderId;
}
