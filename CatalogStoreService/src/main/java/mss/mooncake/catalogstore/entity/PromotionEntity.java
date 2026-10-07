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
}
