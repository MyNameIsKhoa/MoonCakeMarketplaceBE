package mss.mooncake.catalogstore.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import mss.mooncake.catalogstore.enums.PromotionStatus;

@Entity
@Table(name = "promotion")
@AttributeOverride(name = "id", column = @Column(name = "promotion_id"))
public class PromotionEntity extends BaseCatalogEntity {
    @Column(name = "store_id", nullable = false) private Long storeId;
    @Column(name = "name", nullable = false, length = 255) private String name;
    @Column(name = "start_at", nullable = false) private LocalDateTime startAt;
    @Column(name = "end_at", nullable = false) private LocalDateTime endAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30) private PromotionStatus status;

    // ─── Getters ────────────────────────────────────────────────────────────────
    public Long getStoreId() { return storeId; }
    public String getName() { return name; }
    public LocalDateTime getStartAt() { return startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public PromotionStatus getStatus() { return status; }

    // ─── Setters ────────────────────────────────────────────────────────────────
    // storeId chỉ set khi tạo; không chuyển promotion sang store khác.
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public void setName(String name) { this.name = name; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public void setStatus(PromotionStatus status) { this.status = status; }
}
