package mss.mooncake.commerce.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "cart")
@AttributeOverride(name = "id", column = @Column(name = "cart_id"))
public class CartEntity extends BaseCommerceEntity {
    @Column(name = "customer_id_ref", nullable = false) private Long customerIdRef;
    @Column(name = "status", nullable = false, length = 30) private String status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

    public Long getCustomerIdRef() { return customerIdRef; }
    public void setCustomerIdRef(Long customerIdRef) { this.customerIdRef = customerIdRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
