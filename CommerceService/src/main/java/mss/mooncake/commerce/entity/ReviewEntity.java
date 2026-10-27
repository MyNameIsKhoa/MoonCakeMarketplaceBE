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

    @Column(name = "order_item_id", nullable = false, unique = true)
    private Long orderItemId;

    @Column(name = "customer_id_ref", nullable = false)
    private Long customerIdRef;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "comment", columnDefinition = "NVARCHAR(MAX)")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ReviewEntity() {}

    public ReviewEntity(Long orderItemId, Long customerIdRef, Integer rating, String comment, LocalDateTime createdAt) {
        this.orderItemId = orderItemId;
        this.customerIdRef = customerIdRef;
        this.rating = rating;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }

    public Long getCustomerIdRef() { return customerIdRef; }
    public void setCustomerIdRef(Long customerIdRef) { this.customerIdRef = customerIdRef; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
