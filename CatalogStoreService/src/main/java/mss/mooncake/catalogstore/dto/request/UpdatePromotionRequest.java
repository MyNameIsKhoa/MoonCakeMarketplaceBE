package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** Field null = giữ nguyên. Không cho đổi storeId; rules thay qua PUT /rules. status: DRAFT | ACTIVE | DISABLED. */
public record UpdatePromotionRequest(
        @Size(min = 1, max = 255) String name,
        LocalDateTime startAt,
        LocalDateTime endAt,
        String status) { }
