package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "review")
@AttributeOverride(name = "id", column = @Column(name = "review_id"))
public class ReviewEntity extends BaseCommerceEntity {
    @Column(name = "order_item_id", nullable = false, unique = true) private Long orderItemId;
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    @Column(name = "rating", nullable = false) private Integer rating;
    @Column(name = "comment", columnDefinition = "NVARCHAR(MAX)") private String comment;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
}
